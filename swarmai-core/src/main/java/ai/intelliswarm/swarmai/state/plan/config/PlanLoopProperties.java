package ai.intelliswarm.swarmai.state.plan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SwarmAI plan-loop. Inert by default —
 * set {@code swarmai.plan-loop.enabled=true} to activate the auto-config.
 *
 * <pre>
 * swarmai:
 *   plan-loop:
 *     enabled: true
 *     tier:
 *       enabled: true
 *     drift-budget:
 *       enabled: true
 *       budget: 5
 *     bandit:
 *       enabled: false              # off by default; opt-in for learning
 *       warmup: 0
 *       compose: OBSERVER           # OBSERVER | VETO | BYPASS
 *       gate:
 *         min-observations: 30
 *         max-ci-half-width: 0.10
 *       persistence:
 *         file: /var/lib/swarmai/bandit-state.json   # null = in-memory
 *     replanner:
 *       enabled: false
 *       trigger: REJECTION          # REJECTION | TERMINAL | EVERY_N | ALWAYS | NEVER
 *       every-n: 1
 *       regate-amendments: false
 *       system-prompt-override: ""  # blank = use LlmReplanner default
 * </pre>
 */
@ConfigurationProperties(prefix = "swarmai.plan-loop")
public class PlanLoopProperties {

    /** Master switch. When false, no plan-loop beans are created. */
    private boolean enabled = false;

    private Tier tier = new Tier();
    private DriftBudget driftBudget = new DriftBudget();
    private Bandit bandit = new Bandit();
    private Replanner replanner = new Replanner();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Tier getTier() { return tier; }
    public void setTier(Tier tier) { this.tier = tier; }

    public DriftBudget getDriftBudget() { return driftBudget; }
    public void setDriftBudget(DriftBudget driftBudget) { this.driftBudget = driftBudget; }

    public Bandit getBandit() { return bandit; }
    public void setBandit(Bandit bandit) { this.bandit = bandit; }

    public Replanner getReplanner() { return replanner; }
    public void setReplanner(Replanner replanner) { this.replanner = replanner; }

    /** Risk-tier policy settings. */
    public static class Tier {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Drift-budget policy settings. */
    public static class DriftBudget {
        private boolean enabled = true;
        private int budget = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBudget() { return budget; }
        public void setBudget(int budget) { this.budget = budget; }
    }

    /** Bandit settings: enabled, warmup, composition mode, promotion gate, persistence. */
    public static class Bandit {
        private boolean enabled = false;
        private int warmup = 0;
        private Compose compose = Compose.OBSERVER;
        private Gate gate = new Gate();
        private Persistence persistence = new Persistence();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWarmup() { return warmup; }
        public void setWarmup(int warmup) { this.warmup = warmup; }
        public Compose getCompose() { return compose; }
        public void setCompose(Compose compose) { this.compose = compose; }
        public Gate getGate() { return gate; }
        public void setGate(Gate gate) { this.gate = gate; }
        public Persistence getPersistence() { return persistence; }
        public void setPersistence(Persistence persistence) { this.persistence = persistence; }

        /**
         * How the bandit is composed into the policy chain:
         * <ul>
         *   <li>{@link #OBSERVER} — bandit attached via trainer only; trainer learns from
         *       channel transitions but the bandit doesn't participate in decisions.</li>
         *   <li>{@link #VETO} — {@code all(static, banditGate)}: bandit can block what
         *       static rules would auto-approve. Conservative.</li>
         *   <li>{@link #BYPASS} — {@code any(all(static), banditGate)}: bandit can bypass
         *       static rules when confident. Permissive.</li>
         * </ul>
         */
        public enum Compose { OBSERVER, VETO, BYPASS }

        public static class Gate {
            private int minObservations = 30;
            private double maxCiHalfWidth = 0.10;
            public int getMinObservations() { return minObservations; }
            public void setMinObservations(int minObservations) { this.minObservations = minObservations; }
            public double getMaxCiHalfWidth() { return maxCiHalfWidth; }
            public void setMaxCiHalfWidth(double maxCiHalfWidth) { this.maxCiHalfWidth = maxCiHalfWidth; }
        }

        public static class Persistence {
            /** When set, FileBanditStateStore writes here; otherwise InMemoryBanditStateStore is used. */
            private String file;
            public String getFile() { return file; }
            public void setFile(String file) { this.file = file; }
        }
    }

    /** Replanner settings: enabled, trigger type, amendment re-gating. */
    public static class Replanner {
        private boolean enabled = false;
        private TriggerType trigger = TriggerType.REJECTION;
        private int everyN = 1;
        private boolean regateAmendments = false;
        private String systemPromptOverride = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public TriggerType getTrigger() { return trigger; }
        public void setTrigger(TriggerType trigger) { this.trigger = trigger; }
        public int getEveryN() { return everyN; }
        public void setEveryN(int everyN) { this.everyN = everyN; }
        public boolean isRegateAmendments() { return regateAmendments; }
        public void setRegateAmendments(boolean regateAmendments) { this.regateAmendments = regateAmendments; }
        public String getSystemPromptOverride() { return systemPromptOverride; }
        public void setSystemPromptOverride(String systemPromptOverride) {
            this.systemPromptOverride = systemPromptOverride != null ? systemPromptOverride : "";
        }

        public enum TriggerType { REJECTION, TERMINAL, EVERY_N, ALWAYS, NEVER }
    }
}
