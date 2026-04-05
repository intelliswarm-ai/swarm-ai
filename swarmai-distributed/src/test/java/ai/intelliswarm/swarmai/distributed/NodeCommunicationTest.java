package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.cluster.NodeCommunicator;
import ai.intelliswarm.swarmai.distributed.consensus.RaftMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NodeCommunicationTest {

    @Test
    void shouldSendAndReceiveVoteRequest() throws Exception {
        AtomicReference<RaftMessage> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // start receiver on port 9201
        try (NodeCommunicator receiver = new NodeCommunicator("receiver", 9201)) {
            receiver.onMessage(msg -> {
                received.set(msg);
                latch.countDown();
            });
            receiver.start();

            // start sender and connect to receiver
            try (NodeCommunicator sender = new NodeCommunicator("sender", 9202)) {
                sender.registerPeer("receiver", "localhost", 9201);
                sender.start();

                // send a VoteRequest
                var voteRequest = new RaftMessage.VoteRequest(1, "sender", -1, 0);
                sender.send("receiver", voteRequest);

                assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive message within 5s");

                RaftMessage msg = received.get();
                assertInstanceOf(RaftMessage.VoteRequest.class, msg);
                RaftMessage.VoteRequest vr = (RaftMessage.VoteRequest) msg;
                assertEquals(1, vr.term());
                assertEquals("sender", vr.candidateId());
            }
        }
    }

    @Test
    void shouldSendAndReceivePartitionResult() throws Exception {
        AtomicReference<RaftMessage> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (NodeCommunicator leader = new NodeCommunicator("leader", 9203)) {
            leader.onMessage(msg -> {
                received.set(msg);
                latch.countDown();
            });
            leader.start();

            try (NodeCommunicator worker = new NodeCommunicator("worker", 9204)) {
                worker.registerPeer("leader", "localhost", 9203);
                worker.start();

                // worker sends partition result to leader
                var result = new RaftMessage.PartitionResult(
                        1, "worker", "partition-0", true,
                        Map.of("vulnerabilities_found", 42, "patches_generated", 38));
                worker.send("leader", result);

                assertTrue(latch.await(5, TimeUnit.SECONDS));

                RaftMessage msg = received.get();
                assertInstanceOf(RaftMessage.PartitionResult.class, msg);
                RaftMessage.PartitionResult pr = (RaftMessage.PartitionResult) msg;
                assertTrue(pr.success());
                assertEquals("partition-0", pr.partitionId());
                assertEquals("worker", pr.nodeId());
            }
        }
    }

    @Test
    void shouldSendAndReceiveAppendEntries() throws Exception {
        AtomicReference<RaftMessage> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (NodeCommunicator follower = new NodeCommunicator("follower", 9205)) {
            follower.onMessage(msg -> {
                received.set(msg);
                latch.countDown();
            });
            follower.start();

            try (NodeCommunicator leaderComm = new NodeCommunicator("leader", 9206)) {
                leaderComm.registerPeer("follower", "localhost", 9205);
                leaderComm.start();

                // leader sends heartbeat (empty AppendEntries)
                var heartbeat = new RaftMessage.AppendEntries(
                        3, "leader", -1, 0, List.of(), 5);
                leaderComm.send("follower", heartbeat);

                assertTrue(latch.await(5, TimeUnit.SECONDS));

                assertInstanceOf(RaftMessage.AppendEntries.class, received.get());
                RaftMessage.AppendEntries ae = (RaftMessage.AppendEntries) received.get();
                assertEquals(3, ae.term());
                assertEquals("leader", ae.leaderId());
                assertEquals(5, ae.leaderCommitIndex());
            }
        }
    }

    @Test
    void shouldBroadcastToAllPeers() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        try (NodeCommunicator peer1 = new NodeCommunicator("peer-1", 9207);
             NodeCommunicator peer2 = new NodeCommunicator("peer-2", 9208);
             NodeCommunicator broadcaster = new NodeCommunicator("broadcaster", 9209)) {

            peer1.onMessage(msg -> latch.countDown());
            peer2.onMessage(msg -> latch.countDown());

            peer1.start();
            peer2.start();
            broadcaster.start();

            broadcaster.registerPeer("peer-1", "localhost", 9207);
            broadcaster.registerPeer("peer-2", "localhost", 9208);

            // broadcast to all peers
            broadcaster.broadcast(new RaftMessage.VoteRequest(1, "broadcaster", -1, 0));

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Both peers should receive the broadcast");
        }
    }
}
