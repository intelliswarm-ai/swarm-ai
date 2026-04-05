package ai.intelliswarm.swarmai.distributed.consensus;

import java.util.List;
import java.util.Map;

/**
 * RAFT protocol messages — the communication primitives between cluster nodes.
 */
public sealed interface RaftMessage {

    long term();

    /**
     * RequestVote RPC — sent by candidates during elections.
     */
    record VoteRequest(
            long term,
            String candidateId,
            long lastLogIndex,
            long lastLogTerm
    ) implements RaftMessage {}

    /**
     * Vote response — granted or denied.
     */
    record VoteResponse(
            long term,
            String voterId,
            boolean voteGranted
    ) implements RaftMessage {}

    /**
     * AppendEntries RPC — sent by leader to replicate log and serve as heartbeat.
     * Empty entries = heartbeat.
     */
    record AppendEntries(
            long term,
            String leaderId,
            long prevLogIndex,
            long prevLogTerm,
            List<RaftLog.LogEntry> entries,
            long leaderCommitIndex
    ) implements RaftMessage {}

    /**
     * AppendEntries response — success or failure with conflict info.
     */
    record AppendEntriesResponse(
            long term,
            String followerId,
            boolean success,
            long matchIndex
    ) implements RaftMessage {}

    /**
     * Goal-level messages — domain-specific extensions to RAFT.
     */
    record GoalProposal(
            long term,
            String proposerId,
            String goalName,
            Map<String, Object> goalSpec
    ) implements RaftMessage {}

    /**
     * Partition assignment notification.
     */
    record PartitionAssignment(
            long term,
            String leaderId,
            String targetNodeId,
            String partitionId,
            Map<String, Object> partitionData
    ) implements RaftMessage {}

    /**
     * Partition result submission from worker to leader.
     */
    record PartitionResult(
            long term,
            String nodeId,
            String partitionId,
            boolean success,
            Map<String, Object> result
    ) implements RaftMessage {}
}
