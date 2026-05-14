package ai.intelliswarm.swarmai.agent.parallel;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which pending tool calls may run in parallel. Two-axis check:
 *
 * <ol>
 *   <li>Tool-level allowlist/denylist — read tools, calculators, search are
 *       always-safe; clarify / approval are never-safe; everything else
 *       falls through to per-arg analysis.</li>
 *   <li>Path-overlap detection — two write tools whose path arguments share
 *       a common prefix can't run in parallel (one might create the parent
 *       directory the other reads from, or both try to write the same file).</li>
 * </ol>
 *
 * <p>Output is a list of {@link ParallelGroup}s, dispatched in order — every
 * call in group {@code N} must complete before group {@code N+1} starts.
 *
 * <p>Conservative by default: when in doubt, splits into its own group. Better
 * to serialise something that could have run in parallel than to corrupt state.
 */
@PublicApi(since = "1.0.24")
public final class ToolCallSafetyAnalyzer {

    /** Tools that are always safe to parallelize — no observable side effects. */
    private final Set<String> parallelSafeTools;
    /** Tools that must never run in parallel — even with themselves. */
    private final Set<String> neverParallelTools;

    private static final Set<String> DEFAULT_PARALLEL_SAFE = Set.of(
            "Read", "FileRead", "file_read",
            "Grep", "grep",
            "Glob", "glob",
            "WebSearch", "web_search",
            "WebFetch", "web_fetch",
            "Calculator", "calculator",
            "SemanticSearch", "semantic_search",
            "DirectoryRead", "directory_read"
    );

    private static final Set<String> DEFAULT_NEVER_PARALLEL = Set.of(
            "AskUserQuestion", "ask_user_question", "clarify",
            "ApprovalGate", "approval", "approval_gate",
            "WaitForSignal", "wait_for_signal",
            "TaskCreate", "task_create",
            "TaskUpdate", "task_update"
    );

    /** Argument keys that conventionally hold a filesystem path. */
    private static final List<String> PATH_KEYS = List.of(
            "path", "filePath", "file_path", "filename",
            "src", "source", "from", "dest", "destination", "to",
            "directory", "dir", "target"
    );

    public ToolCallSafetyAnalyzer() {
        this(DEFAULT_PARALLEL_SAFE, DEFAULT_NEVER_PARALLEL);
    }

    public ToolCallSafetyAnalyzer(Set<String> parallelSafeTools, Set<String> neverParallelTools) {
        this.parallelSafeTools = Set.copyOf(parallelSafeTools);
        this.neverParallelTools = Set.copyOf(neverParallelTools);
    }

    /**
     * Analyse a batch of pending calls and produce ordered groups. An empty
     * input returns an empty list.
     */
    public List<ParallelGroup> analyse(List<PendingToolCall> calls) {
        Objects.requireNonNull(calls, "calls must not be null");
        if (calls.isEmpty()) {
            return List.of();
        }

        List<ParallelGroup> groups = new ArrayList<>();
        List<PendingToolCall> current = new ArrayList<>();

        for (PendingToolCall call : calls) {
            // never-parallel: flush current group, emit this as singleton, start fresh
            if (neverParallelTools.contains(call.toolName())) {
                if (!current.isEmpty()) {
                    groups.add(new ParallelGroup(List.copyOf(current)));
                    current.clear();
                }
                groups.add(new ParallelGroup(List.of(call)));
                continue;
            }

            // always-safe: can join the current group
            if (parallelSafeTools.contains(call.toolName())) {
                current.add(call);
                continue;
            }

            // path-overlap check against every call already in current group
            boolean conflicts = current.stream().anyMatch(prev -> conflictsWith(prev, call));
            if (conflicts) {
                groups.add(new ParallelGroup(List.copyOf(current)));
                current.clear();
            }
            current.add(call);
        }

        if (!current.isEmpty()) {
            groups.add(new ParallelGroup(List.copyOf(current)));
        }
        return List.copyOf(groups);
    }

    // ---------- conflict detection ----------

    boolean conflictsWith(PendingToolCall a, PendingToolCall b) {
        // Two writers to the same / overlapping path conflict.
        Set<String> aPaths = extractPaths(a);
        Set<String> bPaths = extractPaths(b);
        if (aPaths.isEmpty() && bPaths.isEmpty()) {
            // Neither has a recognisable path argument — fall back to "same tool both writers": serialise.
            return a.toolName().equalsIgnoreCase(b.toolName());
        }
        for (String pa : aPaths) {
            for (String pb : bPaths) {
                if (pathsOverlap(pa, pb)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean pathsOverlap(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalise(a);
        String nb = normalise(b);
        return na.equals(nb)
                || na.startsWith(nb + "/")
                || nb.startsWith(na + "/");
    }

    private static String normalise(String path) {
        // Collapse trailing slashes; lowercase on a case-insensitive convention is OUT of scope
        // (Unix paths are case-sensitive). Just strip duplicate slashes + trailing slash.
        String collapsed = path.replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private static Set<String> extractPaths(PendingToolCall call) {
        Set<String> out = new HashSet<>();
        for (String key : PATH_KEYS) {
            Object v = call.arguments().get(key);
            if (v instanceof String s && !s.isBlank()) {
                out.add(s);
            } else if (v instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    // ---------- registry mutations ----------

    public ToolCallSafetyAnalyzer withAdditionalParallelSafe(String... toolNames) {
        Set<String> merged = new HashSet<>(parallelSafeTools);
        merged.addAll(Arrays.asList(toolNames));
        return new ToolCallSafetyAnalyzer(merged, neverParallelTools);
    }

    public ToolCallSafetyAnalyzer withAdditionalNeverParallel(String... toolNames) {
        Set<String> merged = new HashSet<>(neverParallelTools);
        merged.addAll(Arrays.asList(toolNames));
        return new ToolCallSafetyAnalyzer(parallelSafeTools, merged);
    }

    public Set<String> parallelSafeTools() {
        return parallelSafeTools.stream().collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> neverParallelTools() {
        return neverParallelTools.stream().collect(Collectors.toUnmodifiableSet());
    }
}
