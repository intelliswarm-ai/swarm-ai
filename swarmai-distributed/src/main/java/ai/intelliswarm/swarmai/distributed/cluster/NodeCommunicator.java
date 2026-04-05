package ai.intelliswarm.swarmai.distributed.cluster;

import ai.intelliswarm.swarmai.distributed.consensus.RaftMessage;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TCP-based inter-node communication for distributed SwarmAI clusters.
 *
 * <p>Each node runs a lightweight TCP server that receives RAFT messages
 * and goal-level coordination messages from peers. This enables true
 * multi-JVM distributed execution where agents on different machines
 * collaborate toward a shared goal.</p>
 *
 * <h3>Protocol</h3>
 * <ul>
 *   <li>Messages are JSON-serialized {@link RaftMessage} instances</li>
 *   <li>Each message is length-prefixed (4-byte big-endian int + UTF-8 payload)</li>
 *   <li>Heartbeats double as RAFT AppendEntries with empty entry list</li>
 *   <li>Partition results flow from worker → leader via PartitionResult messages</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * NodeCommunicator comm = new NodeCommunicator("node-1", 9100, objectMapper);
 * comm.onMessage(msg -> raftNode.handleMessage(msg));
 * comm.start();
 *
 * // send to peer
 * comm.send("node-2", "host2", 9100, voteRequest);
 * }</pre>
 */
public class NodeCommunicator implements RaftNode.MessageTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeCommunicator.class);

    private final String nodeId;
    private final int listenPort;
    private final ObjectMapper mapper;
    private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final ExecutorService serverExecutor;
    private final ExecutorService workerPool;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;
    private volatile Consumer<RaftMessage> messageHandler;

    public NodeCommunicator(String nodeId, int listenPort, ObjectMapper mapper) {
        this.nodeId = nodeId;
        this.listenPort = listenPort;
        this.mapper = mapper != null ? mapper : createDefaultMapper();
        this.serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "node-comm-server-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        this.workerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "node-comm-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public NodeCommunicator(String nodeId, int listenPort) {
        this(nodeId, listenPort, null);
    }

    public void onMessage(Consumer<RaftMessage> handler) {
        this.messageHandler = handler;
    }

    /**
     * Start the TCP server and begin accepting connections from peers.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        running = true;

        serverExecutor.submit(() -> {
            log.info("Node {} listening on port {}", nodeId, listenPort);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    workerPool.submit(() -> handleConnection(client));
                } catch (IOException e) {
                    if (running) log.error("Accept failed: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Register a peer node for outbound communication.
     */
    public void registerPeer(String peerId, String host, int port) {
        peerConnections.put(peerId, new PeerConnection(peerId, host, port));
        log.info("Registered peer {} at {}:{}", peerId, host, port);
    }

    /**
     * Send a RAFT message to a specific peer — implements MessageTransport interface.
     */
    @Override
    public void send(String targetNodeId, RaftMessage message) {
        PeerConnection peer = peerConnections.get(targetNodeId);
        if (peer == null) {
            log.warn("Unknown peer: {}", targetNodeId);
            return;
        }

        workerPool.submit(() -> {
            try {
                sendToPeer(peer, message);
            } catch (IOException e) {
                log.warn("Failed to send to {}: {}", targetNodeId, e.getMessage());
            }
        });
    }

    /**
     * Broadcast a message to all registered peers.
     */
    public void broadcast(RaftMessage message) {
        for (String peerId : peerConnections.keySet()) {
            send(peerId, message);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            log.debug("Error closing server socket: {}", e.getMessage());
        }
        serverExecutor.shutdownNow();
        workerPool.shutdownNow();
        peerConnections.values().forEach(PeerConnection::close);
        log.info("Node {} communicator stopped", nodeId);
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private void handleConnection(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            while (running && !socket.isClosed()) {
                int length = in.readInt();
                byte[] payload = new byte[length];
                in.readFully(payload);

                String json = new String(payload, StandardCharsets.UTF_8);
                RaftMessage message = deserializeMessage(json);

                if (message != null && messageHandler != null) {
                    messageHandler.accept(message);
                }
            }
        } catch (EOFException e) {
            // connection closed by peer — normal
        } catch (IOException e) {
            if (running) log.debug("Connection error: {}", e.getMessage());
        }
    }

    private void sendToPeer(PeerConnection peer, RaftMessage message) throws IOException {
        String json = serializeMessage(message);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        Socket socket = peer.getOrConnect();
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private String serializeMessage(RaftMessage message) throws IOException {
        // wrap with type discriminator for deserialization
        Map<String, Object> envelope = Map.of(
                "type", message.getClass().getSimpleName(),
                "payload", mapper.convertValue(message, Map.class)
        );
        return mapper.writeValueAsString(envelope);
    }

    @SuppressWarnings("unchecked")
    private RaftMessage deserializeMessage(String json) {
        try {
            Map<String, Object> envelope = mapper.readValue(json, Map.class);
            String type = (String) envelope.get("type");
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            String payloadJson = mapper.writeValueAsString(payload);

            return switch (type) {
                case "VoteRequest" -> mapper.readValue(payloadJson, RaftMessage.VoteRequest.class);
                case "VoteResponse" -> mapper.readValue(payloadJson, RaftMessage.VoteResponse.class);
                case "AppendEntries" -> mapper.readValue(payloadJson, RaftMessage.AppendEntries.class);
                case "AppendEntriesResponse" -> mapper.readValue(payloadJson, RaftMessage.AppendEntriesResponse.class);
                case "GoalProposal" -> mapper.readValue(payloadJson, RaftMessage.GoalProposal.class);
                case "PartitionAssignment" -> mapper.readValue(payloadJson, RaftMessage.PartitionAssignment.class);
                case "PartitionResult" -> mapper.readValue(payloadJson, RaftMessage.PartitionResult.class);
                default -> {
                    log.warn("Unknown message type: {}", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Failed to deserialize message: {}", e.getMessage());
            return null;
        }
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public String nodeId() { return nodeId; }
    public int listenPort() { return listenPort; }
    public boolean isRunning() { return running; }

    // ─── Peer Connection ────────────────────────────────────────────────

    private static class PeerConnection {
        private final String peerId;
        private final String host;
        private final int port;
        private volatile Socket socket;

        PeerConnection(String peerId, String host, int port) {
            this.peerId = peerId;
            this.host = host;
            this.port = port;
        }

        synchronized Socket getOrConnect() throws IOException {
            if (socket == null || socket.isClosed()) {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(host, port), 5000);
            }
            return socket;
        }

        void close() {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
        }
    }
}
