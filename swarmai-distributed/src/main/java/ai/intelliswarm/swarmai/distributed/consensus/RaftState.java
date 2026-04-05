package ai.intelliswarm.swarmai.distributed.consensus;

/**
 * RAFT consensus node states.
 *
 * <p>State machine: FOLLOWER → CANDIDATE → LEADER.
 * On election timeout: FOLLOWER → CANDIDATE.
 * On winning election: CANDIDATE → LEADER.
 * On discovering higher term: any → FOLLOWER.</p>
 */
public enum RaftState {

    /** Passive node — accepts log entries from leader, votes in elections. */
    FOLLOWER,

    /** Requesting votes — transitions to LEADER on majority, or back to FOLLOWER on timeout/higher term. */
    CANDIDATE,

    /** Active coordinator — replicates log entries, manages partitions, drives reconciliation. */
    LEADER;
}
