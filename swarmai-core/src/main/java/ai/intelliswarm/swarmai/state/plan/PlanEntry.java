package ai.intelliswarm.swarmai.state.plan;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One element of an {@link EvolvingPlan}: a single proposed, approved, executed,
 * or superseded action. Entries are append-only — to "change" an entry, append
 * a new one whose {@link #supersedes()} points at the old one and mark the old
 * one's status to {@link Status#SUPERSEDED}. The history is the audit trail.
 *
 * <p>The {@link #payload()} is intentionally typed as {@link Object} so this
 * core type stays free of {@code swarmai-tools} dependencies. Common payload
 * types include:
 * <ul>
 *   <li>{@code MutationPlan} from {@code tool/safety} — the proposed mutating
 *       operation a tool would perform</li>
 *   <li>A reasoning step or observation emitted by a replanner / agent reflection
 *       (no execution side effect)</li>
 *   <li>An operator amendment (a directly-injected entry)</li>
 * </ul>
 * Consumers downcast based on {@link #proposedBy()} or {@link #payloadType()}.
 *
 * <p>Entries are value objects: equality and hashing follow {@link #id()}.
 */
public final class PlanEntry {

    /** Lifecycle position of an entry within the {@link EvolvingPlan}. */
    public enum Status {
        /** Captured but not yet approved — agent or replanner suggestion. */
        PROPOSED,
        /** Operator (or policy) approved this entry; a guard may now let it through. */
        APPROVED,
        /** Executed and observed; payload reached the world. */
        EXECUTED,
        /** Operator (or policy) refused this entry; never executes. */
        REJECTED,
        /** Replaced by a later entry (see {@link PlanEntry#supersedes()} on the replacement). */
        SUPERSEDED
    }

    private final String id;
    private final String proposedBy;
    private final Instant proposedAt;
    private final Object payload;
    private final String payloadType;
    private final Risk risk;
    private final Status status;
    private final String supersedes;
    private final String reason;
    private final String decidedBy;
    private final Map<String, Object> metadata;

    private PlanEntry(String id,
                      String proposedBy,
                      Instant proposedAt,
                      Object payload,
                      String payloadType,
                      Risk risk,
                      Status status,
                      String supersedes,
                      String reason,
                      String decidedBy,
                      Map<String, Object> metadata) {
        this.id = id;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
        this.payload = payload;
        this.payloadType = payloadType;
        this.risk = risk;
        this.status = status;
        this.supersedes = supersedes;
        this.reason = reason;
        this.decidedBy = decidedBy;
        this.metadata = metadata;
    }

    public String id() { return id; }
    public String proposedBy() { return proposedBy; }
    public Instant proposedAt() { return proposedAt; }
    public Object payload() { return payload; }
    public String payloadType() { return payloadType; }
    public Risk risk() { return risk; }
    public Status status() { return status; }
    public String supersedes() { return supersedes; }
    public String reason() { return reason; }
    /**
     * Identity of the actor whose action moved the entry to its current
     * {@link #status()} — e.g. {@code "PLAN_POLICY"} when a learned policy
     * auto-approved, an operator id like {@code "alice"} when a human acted,
     * or {@code null} when the entry hasn't transitioned yet.
     */
    public String decidedBy() { return decidedBy; }
    public Map<String, Object> metadata() { return metadata; }

    /** Returns a copy with a new status and reason; decidedBy preserved. */
    public PlanEntry withStatus(Status newStatus, String newReason) {
        return withStatus(newStatus, newReason, decidedBy);
    }

    /**
     * Returns a copy with a new status, reason, and decidedBy attribution.
     * Use this overload when an actor (policy / operator / replanner) is
     * driving the transition; the trainer relies on {@code decidedBy} to
     * decide whose decisions to score.
     */
    public PlanEntry withStatus(Status newStatus, String newReason, String newDecidedBy) {
        Objects.requireNonNull(newStatus, "newStatus");
        return new PlanEntry(id, proposedBy, proposedAt, payload, payloadType, risk,
                newStatus, supersedes,
                newReason != null ? newReason : reason,
                newDecidedBy,
                metadata);
    }

    /**
     * Returns a copy with the provided metadata replacing the existing map.
     * Status, reason, decidedBy, and all other fields are preserved. Useful
     * when a side-channel observer (e.g. a {@link
     * ai.intelliswarm.swarmai.agent.subagent.Subagent} reporting its verdict)
     * needs to annotate an entry without changing its lifecycle status.
     *
     * <p>Use {@link #withMetadataPut(String, Object)} when you want to merge
     * a single key into the existing map rather than replace it entirely.
     */
    public PlanEntry withMetadata(Map<String, Object> newMetadata) {
        return new PlanEntry(id, proposedBy, proposedAt, payload, payloadType, risk,
                status, supersedes, reason, decidedBy,
                newMetadata != null ? Map.copyOf(newMetadata) : Map.of());
    }

    /**
     * Returns a copy whose metadata has {@code key → value} merged in
     * (overwriting any existing value for {@code key}). All other fields are
     * preserved.
     */
    public PlanEntry withMetadataPut(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> next = new LinkedHashMap<>(metadata);
        next.put(key, value);
        return withMetadata(next);
    }

    public boolean isActive() {
        return status == Status.PROPOSED || status == Status.APPROVED;
    }

    public boolean isTerminal() {
        return status == Status.EXECUTED || status == Status.REJECTED || status == Status.SUPERSEDED;
    }

    public static Builder builder() { return new Builder(); }

    /** Convenience: a fresh PROPOSED entry with the given source and payload. */
    public static PlanEntry proposed(String proposedBy, Object payload) {
        return builder().proposedBy(proposedBy).payload(payload).build();
    }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String proposedBy = "";
        private Instant proposedAt = Instant.now();
        private Object payload;
        private String payloadType;
        private Risk risk = Risk.UNKNOWN;
        private Status status = Status.PROPOSED;
        private String supersedes;
        private String reason = "";
        private String decidedBy;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder id(String id) { this.id = Objects.requireNonNull(id, "id"); return this; }
        public Builder proposedBy(String s) { this.proposedBy = Objects.requireNonNull(s, "proposedBy"); return this; }
        public Builder proposedAt(Instant t) { this.proposedAt = Objects.requireNonNull(t, "proposedAt"); return this; }
        public Builder payload(Object p) { this.payload = p; return this; }
        public Builder payloadType(String t) { this.payloadType = t; return this; }
        public Builder risk(Risk r) { this.risk = Objects.requireNonNull(r, "risk"); return this; }
        public Builder status(Status s) { this.status = Objects.requireNonNull(s, "status"); return this; }
        public Builder supersedes(String otherId) { this.supersedes = otherId; return this; }
        public Builder reason(String r) { this.reason = r != null ? r : ""; return this; }
        public Builder decidedBy(String d) { this.decidedBy = d; return this; }
        public Builder metadata(String key, Object value) {
            this.metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }
        public Builder metadata(Map<String, Object> m) {
            if (m != null) this.metadata.putAll(m);
            return this;
        }

        public PlanEntry build() {
            if (proposedBy.isBlank()) {
                throw new IllegalStateException("proposedBy is required");
            }
            String resolvedType = payloadType;
            if (resolvedType == null) {
                resolvedType = payload != null ? payload.getClass().getName() : "null";
            }
            return new PlanEntry(id, proposedBy, proposedAt, payload, resolvedType,
                    risk, status, supersedes, reason, decidedBy, Map.copyOf(metadata));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlanEntry that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "PlanEntry{id=" + id + ", by=" + proposedBy +
                ", status=" + status +
                (decidedBy != null ? ", decidedBy=" + decidedBy : "") +
                ", payloadType=" + payloadType + "}";
    }
}
