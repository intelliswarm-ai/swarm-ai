package ai.intelliswarm.swarmai.tool.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Three sibling rule lists evaluated deny-first, then ask, then allow. Mirrors
 * Claude Code's settings file shape: each list is an array of
 * {@link PermissionRule#toString()}-compatible strings.
 *
 * <p>Evaluation:
 * <ol>
 *   <li>If any rule in {@code deny} matches → {@link PermissionDecision#DENY}</li>
 *   <li>else if any rule in {@code ask} matches → {@link PermissionDecision#ASK}</li>
 *   <li>else if any rule in {@code allow} matches → {@link PermissionDecision#ALLOW}</li>
 *   <li>else → {@link PermissionDecision#UNMATCHED} (mode decides)</li>
 * </ol>
 *
 * <p>This is intentionally read-mostly; use {@link Builder} to assemble rule sets.
 * For the merge semantics across settings scopes (managed > flags > local >
 * project > user), call {@link #merge(PermissionRules...)} — later args win on
 * duplicates within the same list.
 */
@PublicApi(since = "1.0.24")
public final class PermissionRules {

    private static final PermissionRules EMPTY =
            new PermissionRules(List.of(), List.of(), List.of());

    private final List<PermissionRule> deny;
    private final List<PermissionRule> ask;
    private final List<PermissionRule> allow;

    private PermissionRules(List<PermissionRule> deny, List<PermissionRule> ask, List<PermissionRule> allow) {
        this.deny = List.copyOf(deny);
        this.ask = List.copyOf(ask);
        this.allow = List.copyOf(allow);
    }

    public static PermissionRules empty() {
        return EMPTY;
    }

    public List<PermissionRule> deny() { return deny; }
    public List<PermissionRule> ask() { return ask; }
    public List<PermissionRule> allow() { return allow; }

    /** Deny-first evaluation. */
    public PermissionDecision evaluate(PermissionRequest req) {
        Objects.requireNonNull(req, "req must not be null");
        for (PermissionRule r : deny) {
            if (r.matches(req)) return PermissionDecision.DENY;
        }
        for (PermissionRule r : ask) {
            if (r.matches(req)) return PermissionDecision.ASK;
        }
        for (PermissionRule r : allow) {
            if (r.matches(req)) return PermissionDecision.ALLOW;
        }
        return PermissionDecision.UNMATCHED;
    }

    /**
     * Merge in scope-precedence order: rules from later arguments take priority
     * over rules from earlier arguments. Matches the settings cascade
     * (managed > flags > local > project > user). All three lists are
     * concatenated; deny-first evaluation still applies inside the merged view.
     */
    public static PermissionRules merge(PermissionRules... layers) {
        Builder b = builder();
        for (PermissionRules layer : layers) {
            if (layer == null) continue;
            for (PermissionRule r : layer.deny) b.deny(r);
            for (PermissionRule r : layer.ask) b.ask(r);
            for (PermissionRule r : layer.allow) b.allow(r);
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private final List<PermissionRule> deny = new ArrayList<>();
        private final List<PermissionRule> ask = new ArrayList<>();
        private final List<PermissionRule> allow = new ArrayList<>();

        public Builder deny(String rule) {
            deny.addAll(RuleParser.parseAll(rule));
            return this;
        }

        public Builder deny(PermissionRule rule) {
            deny.add(rule);
            return this;
        }

        public Builder ask(String rule) {
            ask.addAll(RuleParser.parseAll(rule));
            return this;
        }

        public Builder ask(PermissionRule rule) {
            ask.add(rule);
            return this;
        }

        public Builder allow(String rule) {
            allow.addAll(RuleParser.parseAll(rule));
            return this;
        }

        public Builder allow(PermissionRule rule) {
            allow.add(rule);
            return this;
        }

        public PermissionRules build() {
            return new PermissionRules(deny, ask, allow);
        }
    }
}
