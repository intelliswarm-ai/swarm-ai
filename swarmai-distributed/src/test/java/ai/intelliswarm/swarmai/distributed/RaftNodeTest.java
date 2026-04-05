package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.consensus.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeTest {

    private Map<String, RaftNode> nodes;
    private InProcessTransport transport;

    @BeforeEach
    void setUp() {
        nodes = new LinkedHashMap<>();
        transport = new InProcessTransport(nodes);
    }

    @Test
    void shouldStartAsFollower() {
        RaftNode node = createNode("node-1");
        node.start();
        try {
            assertEquals(RaftState.FOLLOWER, node.state());
            assertEquals(0, node.currentTerm());
            assertTrue(node.isRunning());
        } finally {
            node.stop();
        }
    }

    @Test
    void shouldElectLeaderInSingleNodeCluster() {
        RaftNode node = createNode("node-1");
        node.start();
        try {
            node.startElection();
            assertEquals(RaftState.LEADER, node.state());
            assertEquals(1, node.currentTerm());
            assertEquals("node-1", node.leaderId());
        } finally {
            node.stop();
        }
    }

    @Test
    void shouldGrantVoteToCandidate() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            var request = new RaftMessage.VoteRequest(1, "candidate-1", -1, 0);
            var response = follower.handleVoteRequest(request);

            assertTrue(response.voteGranted());
            assertEquals("follower", response.voterId());
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldRejectVoteForLowerTerm() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            // first, advance the follower's term
            var request1 = new RaftMessage.VoteRequest(5, "candidate-1", -1, 0);
            follower.handleVoteRequest(request1);

            // now try to get a vote for a lower term
            var request2 = new RaftMessage.VoteRequest(3, "candidate-2", -1, 0);
            var response = follower.handleVoteRequest(request2);

            assertFalse(response.voteGranted());
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldNotVoteTwiceInSameTerm() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            var request1 = new RaftMessage.VoteRequest(1, "candidate-1", -1, 0);
            var response1 = follower.handleVoteRequest(request1);
            assertTrue(response1.voteGranted());

            var request2 = new RaftMessage.VoteRequest(1, "candidate-2", -1, 0);
            var response2 = follower.handleVoteRequest(request2);
            assertFalse(response2.voteGranted());
        } finally {
            follower.stop();
        }
    }

    @Test
    void leaderShouldAppendToLog() {
        RaftNode leader = createNode("leader");
        leader.start();
        leader.startElection();
        try {
            var entry = RaftLog.LogEntry.of(leader.currentTerm(),
                    RaftLog.EntryType.PARTITION_ASSIGNED, "p-1", Map.of("nodeId", "worker-1"));

            long index = leader.propose(entry);

            assertEquals(0, index);
            assertEquals(1, leader.log().size());
        } finally {
            leader.stop();
        }
    }

    @Test
    void followerShouldRejectProposal() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            var entry = RaftLog.LogEntry.of(1,
                    RaftLog.EntryType.PARTITION_ASSIGNED, "p-1", Map.of());

            long index = follower.propose(entry);

            assertEquals(-1, index); // rejected — not leader
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldHandleAppendEntries() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            var entry = RaftLog.LogEntry.of(1,
                    RaftLog.EntryType.PARTITION_ASSIGNED, "p-1", Map.of("nodeId", "w-1"));

            var appendEntries = new RaftMessage.AppendEntries(
                    1, "leader-1", -1, 0, List.of(entry), -1);

            var response = follower.handleAppendEntries(appendEntries);

            assertTrue(response.success());
            assertEquals(0, response.matchIndex());
            assertEquals(1, follower.log().size());
            assertEquals("leader-1", follower.leaderId());
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldRejectAppendEntriesWithStaleTerm() {
        RaftNode follower = createNode("follower");
        follower.start();
        try {
            // advance follower to term 5
            follower.handleVoteRequest(new RaftMessage.VoteRequest(5, "c", -1, 0));

            var appendEntries = new RaftMessage.AppendEntries(
                    3, "old-leader", -1, 0, List.of(), -1);

            var response = follower.handleAppendEntries(appendEntries);

            assertFalse(response.success());
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldCommitEntriesWhenQuorumAcknowledges() {
        RaftNode leader = createNode("leader");
        leader.addPeer(new ClusterNode("follower-1", "localhost", 1));
        leader.addPeer(new ClusterNode("follower-2", "localhost", 2));
        leader.start();
        leader.startElection();

        // simulate vote responses to achieve quorum (need 2 votes: self + 1 follower)
        Map<String, Boolean> votes = new ConcurrentHashMap<>();
        votes.put("leader", true); // self-vote
        leader.handleVoteResponse(
                new RaftMessage.VoteResponse(leader.currentTerm(), "follower-1", true), votes);

        assertEquals(RaftState.LEADER, leader.state());

        List<RaftLog.LogEntry> committed = new CopyOnWriteArrayList<>();
        leader.onCommit(committed::add);

        try {
            var entry = RaftLog.LogEntry.of(leader.currentTerm(),
                    RaftLog.EntryType.GOAL_UPDATED, "goal-1", Map.of("status", "executing"));
            leader.propose(entry);

            // simulate follower-1 acknowledgment of AppendEntries
            leader.handleAppendEntriesResponse(
                    new RaftMessage.AppendEntriesResponse(leader.currentTerm(), "follower-1", true, 0));

            // with leader + follower-1 = 2/3 = quorum → committed
            assertEquals(0, leader.log().commitIndex());
            assertEquals(1, committed.size());
        } finally {
            leader.stop();
        }
    }

    @Test
    void leaderShouldStepDownOnHigherTerm() {
        RaftNode leader = createNode("leader");
        leader.start();
        leader.startElection();
        assertEquals(RaftState.LEADER, leader.state());

        try {
            // receive append entries from a higher-term leader
            var appendEntries = new RaftMessage.AppendEntries(
                    10, "new-leader", -1, 0, List.of(), -1);
            leader.handleAppendEntries(appendEntries);

            assertEquals(RaftState.FOLLOWER, leader.state());
            assertEquals(10, leader.currentTerm());
            assertEquals("new-leader", leader.leaderId());
        } finally {
            leader.stop();
        }
    }

    @Test
    void shouldTrackPeers() {
        RaftNode node = createNode("node-1");
        node.addPeer(new ClusterNode("peer-1", "host1", 1));
        node.addPeer(new ClusterNode("peer-2", "host2", 2));

        assertEquals(2, node.peers().size());

        node.removePeer("peer-1");
        assertEquals(1, node.peers().size());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private RaftNode createNode(String id) {
        RaftNode node = new RaftNode(id, transport);
        nodes.put(id, node);
        return node;
    }

    /**
     * In-process transport that delivers messages directly between nodes in the same JVM.
     */
    static class InProcessTransport implements RaftNode.MessageTransport {
        private final Map<String, RaftNode> nodes;

        InProcessTransport(Map<String, RaftNode> nodes) {
            this.nodes = nodes;
        }

        @Override
        public void send(String targetNodeId, RaftMessage message) {
            RaftNode target = nodes.get(targetNodeId);
            if (target == null) return;

            if (message instanceof RaftMessage.VoteRequest vr) {
                target.handleVoteRequest(vr);
            } else if (message instanceof RaftMessage.AppendEntries ae) {
                target.handleAppendEntries(ae);
            }
        }
    }
}
