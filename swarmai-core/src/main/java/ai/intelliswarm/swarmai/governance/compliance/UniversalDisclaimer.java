package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHook;
import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Universal compliance disclaimer reminder — auto-stamps every prompt with a
 * directive that the resulting output is draft work product requiring human
 * sign-off, not advice / not an instruction to execute.
 *
 * <p>Patterned after Anthropic's financial-services repo, which prepends a
 * standard disclaimer to every agent. The disclaimer text is provider-neutral
 * by default but customisable via {@link Builder#text(String)}.
 *
 * <p>Implemented as a {@link LifecycleHook} that returns
 * {@link HookResult#modify(Object)} on {@link LifecycleEvent.UserPromptSubmit}
 * with the original prompt rewritten to include the disclaimer header.
 */
@PublicApi(since = "1.0.24")
public final class UniversalDisclaimer implements LifecycleHook {

    /** Standard text for FS workflows — overridable via builder. */
    public static final String DEFAULT_TEXT =
            "[COMPLIANCE NOTE — auto-stamped] The output you produce is draft work product. "
                    + "It requires human review and explicit sign-off before delivery. "
                    + "Do not present any figure, recommendation, or claim as advice or as "
                    + "an instruction to execute. Every quantitative claim must carry an "
                    + "inline citation back to a source document.";

    private final String text;

    private UniversalDisclaimer(String text) {
        this.text = text;
    }

    /** Standard FS-grade disclaimer. */
    public static UniversalDisclaimer financialServices() {
        return new UniversalDisclaimer(DEFAULT_TEXT);
    }

    public static UniversalDisclaimer withText(String text) {
        return new UniversalDisclaimer(text);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String text() {
        return text;
    }

    @Override
    public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit event) {
        String rewritten = text + "\n\n---\n\n" + event.prompt();
        return HookResult.modify(rewritten);
    }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private String text = DEFAULT_TEXT;

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public UniversalDisclaimer build() {
            return new UniversalDisclaimer(text);
        }
    }
}
