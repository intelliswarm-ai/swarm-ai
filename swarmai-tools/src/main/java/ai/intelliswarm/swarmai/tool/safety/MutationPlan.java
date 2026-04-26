package ai.intelliswarm.swarmai.tool.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of an action a tool is about to perform that may mutate
 * external state (filesystem, processes, OS windows, etc.). Built by the tool
 * before it executes anything; passed to a {@link MutationGuard} so a human or
 * policy can inspect and approve it.
 *
 * <p>A plan is a structured "what I'm about to do" record:
 * <ul>
 *   <li>{@code summary} — one-line, human-readable description for the approver</li>
 *   <li>{@code toolName} — the function name of the tool requesting approval</li>
 *   <li>{@code ops} — the individual operations the tool will perform</li>
 *   <li>{@code riskLevel} — coarse-grained risk to inform policy decisions</li>
 *   <li>{@code metadata} — tool-specific extras for the approver</li>
 * </ul>
 */
public final class MutationPlan {

    /** Coarse-grained risk classification used by policies and UI. */
    public enum RiskLevel {
        /** Read-only or trivially reversible (e.g. mkdir, list). */
        LOW,
        /** Reversible mutations within an allowlisted area (e.g. move within Desktop). */
        MEDIUM,
        /** Hard-to-reverse mutations (e.g. delete to recycle bin, kill non-system process). */
        HIGH,
        /** Irreversible or system-impacting (e.g. permanent delete, kill system process). */
        CRITICAL
    }

    /**
     * A single operation inside a plan. Free-form by design so any tool can
     * describe its work without a fixed type hierarchy.
     *
     * @param type    short verb describing the op (e.g. {@code "move"}, {@code "delete"}, {@code "kill"})
     * @param target  the primary subject of the op (file path, PID, window title, …)
     * @param details additional structured details for display/audit (source/dest, size, …)
     */
    public record Op(String type, String target, Map<String, Object> details) {
        public Op {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(target, "target");
            details = details != null ? Map.copyOf(details) : Map.of();
        }

        public static Op of(String type, String target) {
            return new Op(type, target, Map.of());
        }

        public static Op of(String type, String target, Map<String, Object> details) {
            return new Op(type, target, details);
        }
    }

    private final String summary;
    private final String toolName;
    private final List<Op> ops;
    private final RiskLevel riskLevel;
    private final Map<String, Object> metadata;

    private MutationPlan(String summary, String toolName, List<Op> ops,
                         RiskLevel riskLevel, Map<String, Object> metadata) {
        this.summary = summary;
        this.toolName = toolName;
        this.ops = List.copyOf(ops);
        this.riskLevel = riskLevel;
        this.metadata = Map.copyOf(metadata);
    }

    public String summary() { return summary; }
    public String toolName() { return toolName; }
    public List<Op> ops() { return ops; }
    public RiskLevel riskLevel() { return riskLevel; }
    public Map<String, Object> metadata() { return metadata; }

    /** Convenience: total number of operations in this plan. */
    public int opCount() { return ops.size(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String summary = "";
        private String toolName = "";
        private final List<Op> ops = new ArrayList<>();
        private RiskLevel riskLevel = RiskLevel.MEDIUM;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder summary(String summary) {
            this.summary = Objects.requireNonNull(summary, "summary");
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = Objects.requireNonNull(toolName, "toolName");
            return this;
        }

        public Builder riskLevel(RiskLevel riskLevel) {
            this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
            return this;
        }

        public Builder op(String type, String target) {
            this.ops.add(Op.of(type, target));
            return this;
        }

        public Builder op(String type, String target, Map<String, Object> details) {
            this.ops.add(Op.of(type, target, details));
            return this;
        }

        public Builder op(Op op) {
            this.ops.add(Objects.requireNonNull(op, "op"));
            return this;
        }

        public Builder ops(List<Op> ops) {
            Objects.requireNonNull(ops, "ops");
            this.ops.addAll(ops);
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) this.metadata.putAll(metadata);
            return this;
        }

        public MutationPlan build() {
            if (toolName.isBlank()) {
                throw new IllegalStateException("toolName is required");
            }
            if (summary.isBlank()) {
                throw new IllegalStateException("summary is required");
            }
            return new MutationPlan(summary, toolName,
                    Collections.unmodifiableList(new ArrayList<>(ops)),
                    riskLevel, new LinkedHashMap<>(metadata));
        }
    }

    @Override
    public String toString() {
        return "MutationPlan{tool=" + toolName +
                ", risk=" + riskLevel +
                ", ops=" + ops.size() +
                ", summary='" + summary + "'}";
    }
}
