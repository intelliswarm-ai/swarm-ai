package ai.intelliswarm.swarmai.state.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds an agent-callable {@link ToolCallback} that exposes a
 * {@link UserQuestionResolver} as the {@code ask_user_question} tool. Drop
 * the returned callback into any Spring AI {@code chatClient.prompt().toolCallbacks(...)}
 * invocation and the LLM can pause to ask the user a structured question.
 *
 * <p>Tool input shape (as the LLM sees it):
 * <pre>
 *   {
 *     "question": "Which environment to deploy to?",
 *     "options": ["staging", "production"],
 *     "allowFreeform": false
 *   }
 * </pre>
 *
 * <p>Tool output (as the LLM receives it):
 * <pre>
 *   "User selected: staging"
 *   "User answered (freeform): we want to deploy to a canary cluster first"
 * </pre>
 *
 * <p>Both forms are unambiguous to the LLM — the prefix (selected vs answered)
 * tells the agent whether it received one of its proposed options or a custom
 * answer it should treat as freeform context.
 *
 * <h2>Failure handling</h2>
 * If the resolver throws {@link UserQuestionResolver.UserQuestionException},
 * the tool returns {@code "Error: <message>"} so the LLM can recover. Other
 * runtime exceptions also surface as {@code "Error: ..."} strings. The agent
 * may then retry, fall back, or ask the user something different.
 */
public final class AskUserQuestionTool {

    /** Default function name registered with Spring AI. */
    public static final String FUNCTION_NAME = "ask_user_question";

    private AskUserQuestionTool() {}

    /** Build a callback bound to the given resolver, with the default function name. */
    public static ToolCallback callback(UserQuestionResolver resolver) {
        return callback(resolver, FUNCTION_NAME);
    }

    /** Build a callback with a custom function name (for namespacing in multi-tool agents). */
    public static ToolCallback callback(UserQuestionResolver resolver, String functionName) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(functionName, "functionName");
        Function<AskUserQuestionInput, String> fn = input -> handle(resolver, input);
        return FunctionToolCallback.builder(functionName, fn)
                .description(
                        "Ask the user a question and wait for their answer. Use this "
                                + "when a decision can't be inferred from prior context — "
                                + "e.g. branch choice, target environment, ambiguous file path. "
                                + "Required: question. Optional: options (a list of choices to "
                                + "present), allowFreeform (when true, the user may type a "
                                + "custom answer instead of picking one). Returns either "
                                + "'User selected: <option>' or 'User answered (freeform): <text>'.")
                .inputType(AskUserQuestionInput.class)
                .build();
    }

    static String handle(UserQuestionResolver resolver, AskUserQuestionInput input) {
        if (input == null || input.question == null || input.question.isBlank()) {
            return "Error: 'question' is required and must not be blank.";
        }
        try {
            List<String> options = (input.options == null) ? List.of() : input.options;
            boolean allowFreeform = input.allowFreeform == null
                    ? options.isEmpty()
                    : input.allowFreeform;
            UserQuestion question = new UserQuestion(
                    UUID.randomUUID().toString(),
                    input.question,
                    options,
                    allowFreeform,
                    Instant.now());
            UserAnswer answer = resolver.ask(question);
            if (answer.isFreeform()) {
                return "User answered (freeform): " + answer.value();
            }
            return "User selected: " + answer.value();
        } catch (UserQuestionResolver.UserQuestionException e) {
            return "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Tool input shape, deserialised by Spring AI from the LLM's JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AskUserQuestionInput {
        public String question;
        public List<String> options;
        public Boolean allowFreeform;
    }
}
