package ai.intelliswarm.swarmai.state.plan;

import java.util.List;

/**
 * Produces amendments to an {@link EvolvingPlan}: new entries to append, or
 * supersession requests to replace existing entries. The replanner is the
 * <em>creative</em> component of the plan loop — it reads the current plan
 * and recent observations and decides what changes (if any) are warranted.
 *
 * <p>Implementations range from rule-based heuristics to LLM calls. The SPI
 * itself is pure: it returns amendments rather than mutating a channel, so
 * the caller (typically {@link ReplannerLoop}) can validate, audit, and
 * batch-write the changes.
 *
 * <p>An empty {@link Amendments} return value is the contract for "no
 * changes proposed" — the trigger fired, the replanner looked, but nothing
 * needs to change. Distinct from "haven't run yet" in callers that need to
 * differentiate.
 *
 * <p>Threading: implementations need not be thread-safe. {@link ReplannerLoop}
 * serialises calls.
 */
@FunctionalInterface
public interface Replanner {

    /**
     * Inspect the plan + context and return zero or more amendments.
     *
     * @param plan    the current {@link EvolvingPlan} (snapshot at trigger time)
     * @param context the original goal, recent observations, and any other
     *                inputs the replanner needs
     * @return the proposed amendments; never null, may be empty
     */
    Amendments amend(EvolvingPlan plan, ReplanContext context);

    /**
     * A bundle of amendments returned by a replanner. The loop applies them
     * in order: each {@code newEntries} entry is appended, each
     * {@code supersessions} entry replaces an existing one.
     *
     * <p>Constructed via the builder; immutable.
     *
     * @param newEntries     fresh PROPOSED entries to append; never null, may be empty
     * @param supersessions  pairs of (oldId, replacement) to apply; never null, may be empty
     * @param rationale      human-readable note explaining what the replanner did
     *                       (or "no changes" — used in audit trails); may be empty
     */
    record Amendments(List<PlanEntry> newEntries,
                      List<Supersession> supersessions,
                      String rationale) {

        public Amendments {
            newEntries = List.copyOf(newEntries == null ? List.of() : newEntries);
            supersessions = List.copyOf(supersessions == null ? List.of() : supersessions);
            rationale = rationale != null ? rationale : "";
        }

        /** True when no entries were added and no supersessions proposed. */
        public boolean isEmpty() {
            return newEntries.isEmpty() && supersessions.isEmpty();
        }

        public static Amendments none() {
            return new Amendments(List.of(), List.of(), "no changes");
        }

        public static Amendments none(String rationale) {
            return new Amendments(List.of(), List.of(), rationale);
        }

        public static Amendments of(List<PlanEntry> newEntries) {
            return new Amendments(newEntries, List.of(), "");
        }

        public static Builder builder() {
            return new Builder();
        }

        /** A single supersession: replace {@code oldId}'s entry with {@code replacement}. */
        public record Supersession(String oldId, PlanEntry replacement) {
            public Supersession {
                if (oldId == null || oldId.isBlank()) {
                    throw new IllegalArgumentException("oldId must be set");
                }
                if (replacement == null) {
                    throw new IllegalArgumentException("replacement must be set");
                }
                if (!oldId.equals(replacement.supersedes())) {
                    throw new IllegalArgumentException(
                            "replacement.supersedes() must equal oldId; was "
                                    + replacement.supersedes() + " vs " + oldId);
                }
            }
        }

        public static final class Builder {
            private final java.util.List<PlanEntry> entries = new java.util.ArrayList<>();
            private final java.util.List<Supersession> supersessions = new java.util.ArrayList<>();
            private String rationale = "";

            public Builder addEntry(PlanEntry entry) {
                entries.add(entry);
                return this;
            }

            public Builder supersede(String oldId, PlanEntry replacement) {
                supersessions.add(new Supersession(oldId, replacement));
                return this;
            }

            public Builder rationale(String rationale) {
                this.rationale = rationale != null ? rationale : "";
                return this;
            }

            public Amendments build() {
                return new Amendments(entries, supersessions, rationale);
            }
        }
    }
}
