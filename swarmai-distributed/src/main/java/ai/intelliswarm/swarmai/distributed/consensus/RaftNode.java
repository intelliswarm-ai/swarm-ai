package ai.intelliswarm.swarmai.distributed.consensus;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * RAFT consensus node — implements the full RAFT protocol for distributed agent coordination.
 *
 * <p>Each node in the SwarmAI cluster runs a RaftNode that participates in leader election,
 * log replication, and commitment. The elected leader coordinates work partitioning,
 * tracks goal progress, and drives reconciliation.</p>
 *
 * <h3>RAFT Protocol Implementation</h3>
 * <ul>
 *   <li><b>Leader Election:</b> Randomized election timeout, majority vote wins</li>
 *   <li><b>Log Replication:</b> Leader appends entries and replicates to followers</li>
 *   <li><b>Commitment:</b> Entry committed when majority acknowledges</li>
 *   <li><b>Safety:</b> At most one leader per term, log matching property</li>
 * </ul>
 *
 * <p>Based on the Raft paper by Ongaro and Ousterhout (2014).
 * Adapted for SwarmAI with domain-specific log entry types for goal coordination.</p>
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    private final String nodeId;
    private final RaftLog raftLog;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Map<String, ClusterNode> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();
    private final List<Consumer<RaftLog.LogEntry>> commitListeners = new CopyOnWriteArrayList<>();

    // Persistent state
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;

    // Volatile state
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile String leaderId = null;
    private volatile Instant lastHeartbeat = Instant.now();

    // Election timing
    private final int heartbeatIntervalMs;
    private final int electionTimeoutMinMs;
    private final int electionTimeoutMaxMs;
    private final AtomicLong electionDeadline = new AtomicLong(0);

    // Scheduled executors
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;
    private volatile boolean running = false;

    // Message transport — pluggable for testing and network abstraction
    private final MessageTransport transport;

    public RaftNode(String nodeId, MessageTransport transport,
                    int heartbeatIntervalMs, int electionTimeoutMinMs, int electionTimeoutMaxMs) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.transport = Objects.requireNonNull(transport);
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.electionTimeoutMinMs = electionTimeoutMinMs;
        this.electionTimeoutMaxMs = electionTimeoutMaxMs;
        this.raftLog = new RaftLog();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "raft-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public RaftNode(String nodeId, MessageTransport transport) {
        this(nodeId, transport, 150, 300, 500);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void start() {
        running = true;
        resetElectionTimer();
        log.info("RAFT node {} started as FOLLOWER in term {}", nodeId, currentTerm);
    }

    public void stop() {
        running = false;
        if (electionTimer != null) electionTimer.cancel(false);
        if (heartbeatTimer != null) heartbeatTimer.cancel(false);
        scheduler.shutdown();
        log.info("RAFT node {} stopped", nodeId);
    }

    // ─── Leader Election ────────────────────────────────────────────────

    public void startElection() {
        stateLock.lock();
        try {
            currentTerm++;
            state = RaftState.CANDIDATE;
            votedFor = nodeId;
            leaderId = null;

            log.info("Node {} starting election for term {}", nodeId, currentTerm);

            long lastLogIndex = raftLog.lastIndex();
            long lastLogTerm = raftLog.lastTerm();

            var request = new RaftMessage.VoteRequest(currentTerm, nodeId, lastLogIndex, lastLogTerm);
            int votesNeeded = quorumSize();
            var votesReceived = new ConcurrentHashMap<String, Boolean>();
            votesReceived.put(nodeId, true); // vote for self

            // request votes from all peers
            for (String peerId : peers.keySet()) {
                transport.send(peerId, request);
            }

            // check if we already have quorum (single-node cluster)
            if (countVotes(votesReceived) >= votesNeeded) {
                becomeLeader();
            }
        } finally {
            stateLock.unlock();
        }

        resetElectionTimer();
    }

    public RaftMessage.VoteResponse handleVoteRequest(RaftMessage.VoteRequest request) {
        stateLock.lock();
        try {
            // step down if higher term
            if (request.term() > currentTerm) {
                stepDown(request.term());
            }

            boolean granted = false;
            if (request.term() >= currentTerm
                    && (votedFor == null || votedFor.equals(request.candidateId()))
                    && isLogUpToDate(request.lastLogIndex(), request.lastLogTerm())) {
                granted = true;
                votedFor = request.candidateId();
                resetElectionTimer();
                log.debug("Node {} granted vote to {} for term {}", nodeId, request.candidateId(), request.term());
            }

            return new RaftMessage.VoteResponse(currentTerm, nodeId, granted);
        } finally {
            stateLock.unlock();
        }
    }

    public void handleVoteResponse(RaftMessage.VoteResponse response, Map<String, Boolean> votes) {
        stateLock.lock();
        try {
            if (response.term() > currentTerm) {
                stepDown(response.term());
                return;
            }

            if (state != RaftState.CANDIDATE || response.term() != currentTerm) return;

            votes.put(response.voterId(), response.voteGranted());

            if (countVotes(votes) >= quorumSize()) {
                becomeLeader();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void becomeLeader() {
        state = RaftState.LEADER;
        leaderId = nodeId;

        // initialize nextIndex and matchIndex for each peer
        long lastIndex = raftLog.lastIndex() + 1;
        for (String peerId : peers.keySet()) {
            nextIndex.put(peerId, lastIndex);
            matchIndex.put(peerId, -1L);
        }

        // cancel election timer, start heartbeat
        if (electionTimer != null) electionTimer.cancel(false);
        startHeartbeat();

        log.info("Node {} became LEADER for term {}", nodeId, currentTerm);
    }

    private void stepDown(long newTerm) {
        currentTerm = newTerm;
        state = RaftState.FOLLOWER;
        votedFor = null;
        if (heartbeatTimer != null) heartbeatTimer.cancel(false);
        resetElectionTimer();
        log.debug("Node {} stepped down to FOLLOWER for term {}", nodeId, newTerm);
    }

    // ─── Log Replication ────────────────────────────────────────────────

    /**
     * Propose a new entry — only the leader can append.
     * Returns the log index, or -1 if this node is not the leader.
     */
    public long propose(RaftLog.LogEntry entry) {
        if (state != RaftState.LEADER) return -1;

        RaftLog.LogEntry termEntry = new RaftLog.LogEntry(
                currentTerm, entry.type(), entry.key(), entry.data(), entry.timestamp());
        long index = raftLog.append(termEntry);
        replicateToFollowers();
        // in single-node or small clusters, the leader itself may be sufficient for quorum
        advanceCommitIndex();
        return index;
    }

    public void replicateToFollowers() {
        if (state != RaftState.LEADER) return;

        for (String peerId : peers.keySet()) {
            long nextIdx = nextIndex.getOrDefault(peerId, 0L);
            long prevLogIndex = nextIdx - 1;
            long prevLogTerm = prevLogIndex >= 0
                    ? raftLog.get(prevLogIndex).map(RaftLog.LogEntry::term).orElse(0L)
                    : 0;

            List<RaftLog.LogEntry> entries = raftLog.entriesFrom(nextIdx);

            var appendEntries = new RaftMessage.AppendEntries(
                    currentTerm, nodeId, prevLogIndex, prevLogTerm,
                    entries, raftLog.commitIndex());

            transport.send(peerId, appendEntries);
        }
    }

    public RaftMessage.AppendEntriesResponse handleAppendEntries(RaftMessage.AppendEntries request) {
        stateLock.lock();
        try {
            // higher term — step down
            if (request.term() > currentTerm) {
                stepDown(request.term());
            }

            // reject if stale term
            if (request.term() < currentTerm) {
                return new RaftMessage.AppendEntriesResponse(currentTerm, nodeId, false, -1);
            }

            // accept leader
            leaderId = request.leaderId();
            lastHeartbeat = Instant.now();
            resetElectionTimer();

            if (state == RaftState.CANDIDATE) {
                state = RaftState.FOLLOWER;
            }

            // log consistency check
            if (request.prevLogIndex() >= 0) {
                Optional<RaftLog.LogEntry> prevEntry = raftLog.get(request.prevLogIndex());
                if (prevEntry.isEmpty() || prevEntry.get().term() != request.prevLogTerm()) {
                    return new RaftMessage.AppendEntriesResponse(currentTerm, nodeId, false, raftLog.lastIndex());
                }
            }

            // append new entries
            if (!request.entries().isEmpty()) {
                long insertIndex = request.prevLogIndex() + 1;

                // truncate conflicting entries
                for (int i = 0; i < request.entries().size(); i++) {
                    long idx = insertIndex + i;
                    Optional<RaftLog.LogEntry> existing = raftLog.get(idx);
                    if (existing.isPresent() && existing.get().term() != request.entries().get(i).term()) {
                        raftLog.truncateFrom(idx);
                        break;
                    }
                }

                // append entries not already in log
                for (int i = 0; i < request.entries().size(); i++) {
                    long idx = insertIndex + i;
                    if (idx > raftLog.lastIndex()) {
                        raftLog.append(request.entries().get(i));
                    }
                }
            }

            // update commit index
            if (request.leaderCommitIndex() > raftLog.commitIndex()) {
                raftLog.setCommitIndex(Math.min(request.leaderCommitIndex(), raftLog.lastIndex()));
                applyCommittedEntries();
            }

            return new RaftMessage.AppendEntriesResponse(currentTerm, nodeId, true, raftLog.lastIndex());
        } finally {
            stateLock.unlock();
        }
    }

    public void handleAppendEntriesResponse(RaftMessage.AppendEntriesResponse response) {
        stateLock.lock();
        try {
            if (response.term() > currentTerm) {
                stepDown(response.term());
                return;
            }

            if (state != RaftState.LEADER) return;

            String followerId = response.followerId();

            if (response.success()) {
                matchIndex.put(followerId, response.matchIndex());
                nextIndex.put(followerId, response.matchIndex() + 1);
                advanceCommitIndex();
            } else {
                // decrement nextIndex and retry
                long next = nextIndex.getOrDefault(followerId, 1L) - 1;
                nextIndex.put(followerId, Math.max(0, next));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void advanceCommitIndex() {
        // find the highest index replicated to a majority
        for (long n = raftLog.lastIndex(); n > raftLog.commitIndex(); n--) {
            Optional<RaftLog.LogEntry> entry = raftLog.get(n);
            if (entry.isPresent() && entry.get().term() == currentTerm) {
                long replicatedCount = 1; // count self
                for (long mi : matchIndex.values()) {
                    if (mi >= n) replicatedCount++;
                }
                if (replicatedCount >= quorumSize()) {
                    raftLog.setCommitIndex(n);
                    applyCommittedEntries();
                    break;
                }
            }
        }
    }

    private void applyCommittedEntries() {
        while (raftLog.lastApplied() < raftLog.commitIndex()) {
            long nextToApply = raftLog.lastApplied() + 1;
            raftLog.get(nextToApply).ifPresent(entry -> {
                for (Consumer<RaftLog.LogEntry> listener : commitListeners) {
                    try {
                        listener.accept(entry);
                    } catch (Exception e) {
                        log.warn("Commit listener failed for entry at index {}: {}", nextToApply, e.getMessage());
                    }
                }
                raftLog.setLastApplied(nextToApply);
            });
        }
    }

    // ─── Timers ─────────────────────────────────────────────────────────

    private void resetElectionTimer() {
        if (electionTimer != null) electionTimer.cancel(false);
        if (!running) return;

        int timeout = ThreadLocalRandom.current()
                .nextInt(electionTimeoutMinMs, electionTimeoutMaxMs + 1);
        electionDeadline.set(System.currentTimeMillis() + timeout);

        electionTimer = scheduler.schedule(() -> {
            if (running && state != RaftState.LEADER) {
                startElection();
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel(false);

        heartbeatTimer = scheduler.scheduleAtFixedRate(() -> {
            if (running && state == RaftState.LEADER) {
                replicateToFollowers();
            }
        }, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private boolean isLogUpToDate(long candidateLastLogIndex, long candidateLastLogTerm) {
        long myLastTerm = raftLog.lastTerm();
        long myLastIndex = raftLog.lastIndex();
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= myLastIndex;
    }

    private int quorumSize() {
        return ((peers.size() + 1) / 2) + 1;
    }

    private long countVotes(Map<String, Boolean> votes) {
        return votes.values().stream().filter(Boolean::booleanValue).count();
    }

    // ─── Public Accessors ───────────────────────────────────────────────

    public String nodeId() { return nodeId; }
    public RaftState state() { return state; }
    public long currentTerm() { return currentTerm; }
    public String leaderId() { return leaderId; }
    public String votedFor() { return votedFor; }
    public RaftLog log() { return raftLog; }
    public boolean isLeader() { return state == RaftState.LEADER; }
    public boolean isRunning() { return running; }

    public void addPeer(ClusterNode peer) {
        peers.put(peer.nodeId(), peer);
    }

    public void removePeer(String peerId) {
        peers.remove(peerId);
        nextIndex.remove(peerId);
        matchIndex.remove(peerId);
    }

    public Map<String, ClusterNode> peers() { return Map.copyOf(peers); }

    public void onCommit(Consumer<RaftLog.LogEntry> listener) {
        commitListeners.add(listener);
    }

    /**
     * Pluggable message transport — enables in-process testing
     * and swappable network implementations.
     */
    public interface MessageTransport {
        void send(String targetNodeId, RaftMessage message);
    }
}
