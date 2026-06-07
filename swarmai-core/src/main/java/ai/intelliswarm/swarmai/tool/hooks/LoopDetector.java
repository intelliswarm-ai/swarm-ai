package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Behavioural loop detector. Watches the recent stream of tool calls and
 * trips when one of two patterns appears:
 *
 * <ul>
 *   <li><b>Identical-args repeated failure</b> — same {@code (toolName, args)}
 *       called {@code warnAt} times and failing each time → log a warning.
 *       Called {@code blockAt} times → block via {@link ToolHookResult#deny}.</li>
 *   <li><b>Idempotent-tool same-result loop</b> — same {@code (toolName, args)}
 *       called {@code blockAt} times with the same output → block (the model
 *       is stuck reading the same fact and not making progress).</li>
 * </ul>
 *
 * <p>Window is bounded by {@link #historyLimit} entries; older entries are
 * forgotten. Detection is per-agent (keyed on
 * {@link ToolHookContext#agentId()}) so two agents in the same swarm don't
 * pollute each other's history.
 *
 * <p>Implemented as a pure {@link ToolHook} so it composes cleanly with the
 * existing hook chain — audit, sanitize, rate-limit, deny, loop-detect can
 * all coexist on a single agent.
 */
@PublicApi(since = "1.0.24")
public final class LoopDetector implements ToolHook {

    private final int warnAt;
    private final int blockAt;
    private final int historyLimit;

    /** Per-agent rolling history of (key → recent {@link Entry} records). */
    private final Map<String, Deque<Entry>> history = new HashMap<>();

    public LoopDetector() {
        this(3, 6, 50);
    }

    public LoopDetector(int warnAt, int blockAt, int historyLimit) {
        if (warnAt < 1) throw new IllegalArgumentException("warnAt must be ≥ 1");
        if (blockAt <= warnAt) throw new IllegalArgumentException("blockAt must be > warnAt");
        if (historyLimit < blockAt) throw new IllegalArgumentException("historyLimit must be ≥ blockAt");
        this.warnAt = warnAt;
        this.blockAt = blockAt;
        this.historyLimit = historyLimit;
    }

    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        String key = keyFor(ctx.toolName(), ctx.inputParams());
        Deque<Entry> deque = history.computeIfAbsent(ctx.agentId(), id -> new ArrayDeque<>());

        int identicalFailures = 0;
        int identicalSameResult = 0;
        String prevOutput = null;
        boolean sameOutputRun = true;

        for (Entry e : deque) {
            if (!e.key.equals(key)) continue;
            if (e.error) {
                identicalFailures++;
            }
            if (prevOutput == null) {
                prevOutput = e.output;
            } else if (sameOutputRun && Objects.equals(prevOutput, e.output)) {
                identicalSameResult++;
            } else {
                sameOutputRun = false;
            }
        }

        if (identicalFailures >= blockAt) {
            return ToolHookResult.deny(
                    "LoopDetector: blocking after " + identicalFailures
                            + " identical-args failures of " + ctx.toolName());
        }
        if (identicalFailures >= warnAt) {
            return ToolHookResult.warn(
                    "LoopDetector: " + identicalFailures + " identical-args failures of " + ctx.toolName());
        }
        if (identicalSameResult >= blockAt - 1) {
            return ToolHookResult.deny(
                    "LoopDetector: blocking after " + (identicalSameResult + 1)
                            + " identical-args calls of " + ctx.toolName() + " with identical output");
        }
        return ToolHookResult.allow();
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        String key = keyFor(ctx.toolName(), ctx.inputParams());
        Deque<Entry> deque = history.computeIfAbsent(ctx.agentId(), id -> new ArrayDeque<>());
        deque.addFirst(new Entry(key, ctx.output(), ctx.hasError()));
        while (deque.size() > historyLimit) {
            deque.removeLast();
        }
        return ToolHookResult.allow();
    }

    /** Visible for tests + introspection. */
    public int historySize(String agentId) {
        Deque<Entry> deque = history.get(agentId);
        return deque == null ? 0 : deque.size();
    }

    private static String keyFor(String toolName, Map<String, Object> args) {
        // Sorted-key serialisation so {a:1,b:2} and {b:2,a:1} collide.
        if (args == null || args.isEmpty()) {
            return toolName + "|";
        }
        StringBuilder sb = new StringBuilder(toolName).append('|');
        args.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append(';'));
        return sb.toString();
    }

    private record Entry(String key, String output, boolean error) {}
}
