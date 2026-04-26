package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.ApprovalRequest;
import ai.intelliswarm.swarmai.governance.ApprovalStatus;
import ai.intelliswarm.swarmai.governance.GovernanceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local-dev fallback {@link ApprovalGateHandler} that prints each approval gate
 * to a console and reads {@code y}/{@code n} from stdin. Intended for running
 * supervised-mode tools on a developer machine without {@code swarmai-enterprise}
 * (and its in-memory or external approval UIs) on the classpath.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>Approve:</b> {@code y}, {@code yes}, {@code approve} (case-insensitive)</li>
 *   <li><b>Reject:</b> {@code n}, {@code no}, {@code reject} (case-insensitive)</li>
 *   <li><b>Anything else:</b> rejected with reason "unrecognised input"</li>
 *   <li><b>EOF / closed stdin:</b> rejected (fail-closed)</li>
 *   <li><b>Timeout:</b> calls {@link ApprovalRequest#timeout(boolean)} using the gate's policy</li>
 *   <li><b>Concurrent requests:</b> serialised by an internal lock so prompts never interleave</li>
 * </ul>
 */
public class ConsoleApprovalGateHandler implements ApprovalGateHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleApprovalGateHandler.class);

    private final BufferedReader reader;
    private final PrintStream out;
    private final ConcurrentHashMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final ReentrantLock promptLock = new ReentrantLock();
    private final ExecutorService readerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "swarmai-console-approval");
        t.setDaemon(true);
        return t;
    });

    /** Wraps {@code System.in} / {@code System.err}. */
    public ConsoleApprovalGateHandler() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.err);
    }

    /** Test-friendly constructor. */
    public ConsoleApprovalGateHandler(Reader reader, PrintStream out) {
        this.reader = new BufferedReader(Objects.requireNonNull(reader, "reader"));
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public ApprovalRequest requestApproval(ApprovalGate gate, GovernanceContext context) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(context, "context");

        ApprovalRequest request = new ApprovalRequest(
                gate.gateId(),
                context.swarmId(),
                context.taskId(),
                context.tenantId(),
                "console",
                buildRequestContext(gate, context));
        requests.put(request.getRequestId(), request);

        promptLock.lock();
        try {
            printPrompt(gate, context, request);
            return readDecision(gate, request);
        } finally {
            promptLock.unlock();
        }
    }

    private ApprovalRequest readDecision(ApprovalGate gate, ApprovalRequest request) {
        Future<String> readFuture = readerExecutor.submit(reader::readLine);
        long timeoutMillis = Math.max(1, gate.timeout().toMillis());
        try {
            String line = readFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (line == null) {
                applyReject(request, "SYSTEM", "Console closed (EOF) — fail-closed");
                out.println("[swarmai] No console input available — REJECTED.");
                return request;
            }
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (isApprove(trimmed)) {
                applyApprove(request, "console", "user approved at console");
                out.println("[swarmai] APPROVED.");
            } else if (isReject(trimmed)) {
                applyReject(request, "console", "user rejected at console");
                out.println("[swarmai] REJECTED.");
            } else {
                applyReject(request, "console",
                        "Unrecognised input '" + line + "' — fail-closed");
                out.println("[swarmai] Unrecognised input — REJECTED.");
            }
            return request;
        } catch (TimeoutException e) {
            readFuture.cancel(true);
            boolean autoApprove = gate.policy() != null && gate.policy().autoApproveOnTimeout();
            request.timeout(autoApprove);
            out.println("[swarmai] TIMED OUT — "
                    + (autoApprove ? "auto-approved per policy." : "auto-rejected per policy."));
            return request;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            applyReject(request, "SYSTEM", "Interrupted while waiting for approval");
            return request;
        } catch (Exception e) {
            logger.warn("Error reading console approval", e);
            applyReject(request, "SYSTEM", "Error reading console: " + e.getMessage());
            return request;
        }
    }

    @Override
    public void approve(String requestId, String approver, String reason) {
        ApprovalRequest req = requests.get(requestId);
        if (req == null) {
            logger.warn("approve(): request not found: {}", requestId);
            return;
        }
        req.approve(approver, reason);
    }

    @Override
    public void reject(String requestId, String approver, String reason) {
        ApprovalRequest req = requests.get(requestId);
        if (req == null) {
            logger.warn("reject(): request not found: {}", requestId);
            return;
        }
        req.reject(approver, reason);
    }

    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return requests.values().stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .toList();
    }

    @Override
    public List<ApprovalRequest> getPendingRequests(String tenantId) {
        return requests.values().stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .filter(r -> tenantId != null && tenantId.equals(r.getTenantId()))
                .toList();
    }

    @Override
    public Optional<ApprovalRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    /** Releases the internal reader thread pool. Call from {@code @PreDestroy} when used as a Spring bean. */
    public void shutdown() {
        readerExecutor.shutdownNow();
    }

    // ---- helpers ----

    private static boolean isApprove(String line) {
        return line.equals("y") || line.equals("yes") || line.equals("approve");
    }

    private static boolean isReject(String line) {
        return line.equals("n") || line.equals("no") || line.equals("reject");
    }

    private static void applyApprove(ApprovalRequest req, String approver, String reason) {
        if (!req.approve(approver, reason)) {
            logger.debug("Could not approve {}: status={}", req.getRequestId(), req.getStatus());
        }
    }

    private static void applyReject(ApprovalRequest req, String approver, String reason) {
        if (!req.reject(approver, reason)) {
            logger.debug("Could not reject {}: status={}", req.getRequestId(), req.getStatus());
        }
    }

    private void printPrompt(ApprovalGate gate, GovernanceContext context, ApprovalRequest request) {
        Map<String, Object> meta = context.metadata();
        out.println();
        out.println("=== SwarmAI Approval Request ===");
        out.println("Request:  " + request.getRequestId());
        out.println("Gate:     " + safe(gate.name()));
        if (meta.get("tool") != null)        out.println("Tool:     " + meta.get("tool"));
        if (meta.get("riskLevel") != null)   out.println("Risk:     " + meta.get("riskLevel"));
        if (meta.get("summary") != null)     out.println("Summary:  " + meta.get("summary"));
        if (context.tenantId() != null)      out.println("Tenant:   " + context.tenantId());
        out.println("Timeout:  " + gate.timeout());
        printOps(meta);
        if (gate.description() != null && !gate.description().isBlank()
                && !Objects.equals(gate.description(), meta.get("summary"))) {
            out.println("Detail:   " + gate.description());
        }
        out.print("Approve? [y/N]: ");
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private void printOps(Map<String, Object> meta) {
        Object ops = meta.get("ops");
        if (!(ops instanceof List<?> list) || list.isEmpty()) return;
        out.println("Ops (" + list.size() + "):");
        int shown = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object type = m.get("type");
            Object target = m.get("target");
            Object details = m.get("details");
            String detailText = (details instanceof Map<?, ?> dm && !dm.isEmpty())
                    ? "  " + dm
                    : "";
            out.println("  - " + type + " " + target + detailText);
            if (++shown >= 20) {
                out.println("  … and " + (list.size() - shown) + " more");
                break;
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private Map<String, Object> buildRequestContext(ApprovalGate gate, GovernanceContext context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context.metadata());
        ctx.put("gateName", gate.name());
        ctx.put("gateDescription", gate.description());
        ctx.put("gateTrigger", gate.trigger() == null ? null : gate.trigger().name());
        ctx.put("currentIteration", context.currentIteration());
        return new HashMap<>(ctx);
    }
}
