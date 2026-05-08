package ai.intelliswarm.swarmai.state.question;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the AskUserQuestion primitive (harness primitive). */
class UserQuestionTest {

    // =================================================================
    // UserQuestion record
    // =================================================================
    @Nested
    @DisplayName("UserQuestion record")
    class QuestionRecord {

        @Test
        void freeformFactoryHasNoOptionsAndAllowsFreeform() {
            UserQuestion q = UserQuestion.freeform("What's your favourite colour?");
            assertNotNull(q.id());
            assertEquals("What's your favourite colour?", q.question());
            assertTrue(q.options().isEmpty());
            assertTrue(q.allowFreeform());
            assertTrue(q.isFreeformOnly());
        }

        @Test
        void chooseFactoryProducesPureOptionsQuestion() {
            UserQuestion q = UserQuestion.choose("env?", List.of("staging", "prod"));
            assertEquals(2, q.options().size());
            assertFalse(q.allowFreeform());
            assertFalse(q.isFreeformOnly());
        }

        @Test
        void chooseOrFreeformFactoryAllowsBoth() {
            UserQuestion q = UserQuestion.chooseOrFreeform("env?", List.of("staging", "prod"));
            assertEquals(2, q.options().size());
            assertTrue(q.allowFreeform());
        }

        @Test
        void blankQuestionRejected() {
            assertThrows(IllegalArgumentException.class, () -> UserQuestion.freeform(""));
            assertThrows(IllegalArgumentException.class, () -> UserQuestion.freeform("   "));
        }

        @Test
        void blankOptionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> UserQuestion.choose("env?", List.of("staging", "")));
            // List.of() rejects nulls itself with NPE before our validation,
            // so use a mutable list to test the null-element guard.
            java.util.List<String> withNull = new java.util.ArrayList<>();
            withNull.add("ok");
            withNull.add(null);
            assertThrows(IllegalArgumentException.class,
                    () -> new UserQuestion("id", "q?", withNull, false, Instant.now()));
        }

        @Test
        void nullOptionsBecomesEmptyList() {
            UserQuestion q = new UserQuestion("id", "q?", null, true, Instant.now());
            assertNotNull(q.options());
            assertTrue(q.options().isEmpty());
        }

        @Test
        void optionsAreImmutable() {
            UserQuestion q = UserQuestion.choose("q?", List.of("a", "b"));
            assertThrows(UnsupportedOperationException.class, () -> q.options().add("c"));
        }

        @Test
        void nullAskedAtDefaultsToNow() {
            UserQuestion q = new UserQuestion("id", "q?", List.of(), true, null);
            assertNotNull(q.askedAt());
        }
    }

    // =================================================================
    // UserAnswer record
    // =================================================================
    @Nested
    @DisplayName("UserAnswer record")
    class AnswerRecord {

        @Test
        void ofOptionFactoryProducesOptionAnswer() {
            UserAnswer a = UserAnswer.ofOption("q1", "staging");
            assertEquals("q1", a.questionId());
            assertEquals("staging", a.selectedOption());
            assertNull(a.freeformAnswer());
            assertFalse(a.isFreeform());
            assertEquals("staging", a.value());
        }

        @Test
        void ofFreeformFactoryProducesFreeformAnswer() {
            UserAnswer a = UserAnswer.ofFreeform("q1", "deploy to canary");
            assertEquals("q1", a.questionId());
            assertNull(a.selectedOption());
            assertEquals("deploy to canary", a.freeformAnswer());
            assertTrue(a.isFreeform());
            assertEquals("deploy to canary", a.value());
        }

        @Test
        void bothNonNullRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserAnswer("q1", "staging", "extra", Instant.now()));
        }

        @Test
        void bothNullRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserAnswer("q1", null, null, Instant.now()));
        }

        @Test
        void blankSelectedOptionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserAnswer("q1", "  ", null, Instant.now()));
        }

        @Test
        void blankFreeformRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserAnswer("q1", null, "  ", Instant.now()));
        }

        @Test
        void nullAnsweredAtDefaultsToNow() {
            UserAnswer a = new UserAnswer("q1", "ok", null, null);
            assertNotNull(a.answeredAt());
        }
    }

    // =================================================================
    // ConsoleUserQuestionResolver
    // =================================================================
    @Nested
    @DisplayName("ConsoleUserQuestionResolver")
    class ConsoleResolver {

        private UserAnswer ask(String stdin, UserQuestion q) {
            ByteArrayInputStream in = new ByteArrayInputStream(
                    stdin.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ConsoleUserQuestionResolver resolver =
                    new ConsoleUserQuestionResolver(in, new PrintStream(out));
            return resolver.ask(q);
        }

        private String askCapturingOutput(String stdin, UserQuestion q,
                                          UserAnswer[] result) {
            ByteArrayInputStream in = new ByteArrayInputStream(
                    stdin.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ConsoleUserQuestionResolver resolver =
                    new ConsoleUserQuestionResolver(in, new PrintStream(out));
            result[0] = resolver.ask(q);
            return out.toString(StandardCharsets.UTF_8);
        }

        @Test
        void numericInputSelectsOption() {
            UserAnswer a = ask("2\n", UserQuestion.choose("env?",
                    List.of("staging", "production")));
            assertFalse(a.isFreeform());
            assertEquals("production", a.selectedOption());
        }

        @Test
        void firstOptionSelectableByOne() {
            UserAnswer a = ask("1\n", UserQuestion.choose("env?",
                    List.of("staging", "production")));
            assertEquals("staging", a.selectedOption());
        }

        @Test
        void freeformOnlyQuestionAcceptsTypedAnswer() {
            UserAnswer a = ask("hello there\n", UserQuestion.freeform("greet me?"));
            assertTrue(a.isFreeform());
            assertEquals("hello there", a.value());
        }

        @Test
        void chooseOrFreeformAcceptsCustomAfterC() {
            UserAnswer a = ask("C\nour custom answer\n",
                    UserQuestion.chooseOrFreeform("env?",
                            List.of("staging", "production")));
            assertTrue(a.isFreeform());
            assertEquals("our custom answer", a.value());
        }

        @Test
        void chooseOrFreeformAcceptsTypedAnswerDirectly() {
            // Non-numeric, non-'C' input on a chooseOrFreeform question is
            // treated as a freeform answer.
            UserAnswer a = ask("canary\n",
                    UserQuestion.chooseOrFreeform("env?",
                            List.of("staging", "production")));
            assertTrue(a.isFreeform());
            assertEquals("canary", a.value());
        }

        @Test
        void pureChoiceRejectsCAndReprompts() {
            UserAnswer[] result = new UserAnswer[1];
            String out = askCapturingOutput("C\n2\n",
                    UserQuestion.choose("env?", List.of("staging", "production")),
                    result);
            assertTrue(out.contains("requires picking one of the listed options"),
                    "should reprompt: " + out);
            assertEquals("production", result[0].selectedOption());
        }

        @Test
        void pureChoiceRejectsFreeformInputAndReprompts() {
            UserAnswer[] result = new UserAnswer[1];
            String out = askCapturingOutput("nope\n1\n",
                    UserQuestion.choose("env?", List.of("staging", "production")),
                    result);
            assertTrue(out.contains("please enter an option number"));
            assertEquals("staging", result[0].selectedOption());
        }

        @Test
        void blankInputRepromptsThenAccepts() {
            UserAnswer[] result = new UserAnswer[1];
            String out = askCapturingOutput("\n  \n2\n",
                    UserQuestion.choose("env?", List.of("a", "b")),
                    result);
            assertTrue(out.contains("(please enter an answer)"),
                    "blank line should reprompt: " + out);
            assertEquals("b", result[0].selectedOption());
        }

        @Test
        void outOfRangeOptionRepromptsThenAccepts() {
            UserAnswer[] result = new UserAnswer[1];
            String out = askCapturingOutput("9\n1\n",
                    UserQuestion.choose("env?", List.of("a", "b")),
                    result);
            assertTrue(out.contains("out of range"));
            assertEquals("a", result[0].selectedOption());
        }

        @Test
        void stdinClosedThrows() {
            assertThrows(UserQuestionResolver.UserQuestionException.class,
                    () -> ask("", UserQuestion.freeform("q?")));
        }
    }

    // =================================================================
    // ScriptedUserQuestionResolver
    // =================================================================
    @Nested
    @DisplayName("ScriptedUserQuestionResolver")
    class ScriptedResolver {

        @Test
        void scriptedAnswerMatchingOptionReturnsAsOption() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("staging");
            UserAnswer a = r.ask(UserQuestion.choose("env?",
                    List.of("staging", "production")));
            assertFalse(a.isFreeform());
            assertEquals("staging", a.selectedOption());
        }

        @Test
        void caseInsensitiveOptionMatch() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("STAGING");
            UserAnswer a = r.ask(UserQuestion.choose("env?",
                    List.of("staging", "production")));
            assertEquals("staging", a.selectedOption(),
                    "case-insensitive match returns the canonical option spelling");
        }

        @Test
        void scriptedAnswerForFreeformQuestionReturnsAsFreeform() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("custom answer");
            UserAnswer a = r.ask(UserQuestion.freeform("anything?"));
            assertTrue(a.isFreeform());
            assertEquals("custom answer", a.value());
        }

        @Test
        void scriptedNonMatchingAnswerForChooseOrFreeformReturnsAsFreeform() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("canary");
            UserAnswer a = r.ask(UserQuestion.chooseOrFreeform("env?",
                    List.of("staging", "production")));
            assertTrue(a.isFreeform());
            assertEquals("canary", a.value());
        }

        @Test
        void scriptedNonMatchingAnswerForPureChoiceThrows() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("canary");
            assertThrows(UserQuestionResolver.UserQuestionException.class,
                    () -> r.ask(UserQuestion.choose("env?",
                            List.of("staging", "production"))));
        }

        @Test
        void emptyScriptThrows() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver();
            assertThrows(UserQuestionResolver.UserQuestionException.class,
                    () -> r.ask(UserQuestion.freeform("q?")));
        }

        @Test
        void answersConsumedInOrder() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver(
                    "first", "second", "third");
            assertEquals("first", r.ask(UserQuestion.freeform("q1?")).value());
            assertEquals("second", r.ask(UserQuestion.freeform("q2?")).value());
            assertEquals("third", r.ask(UserQuestion.freeform("q3?")).value());
            assertEquals(0, r.remaining());
        }

        @Test
        void enqueueAddsToScript() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("first");
            r.enqueue("late-bound");
            r.ask(UserQuestion.freeform("q1?"));
            assertEquals("late-bound", r.ask(UserQuestion.freeform("q2?")).value());
        }

        @Test
        void askedHistoryReflectsAllQuestions() {
            ScriptedUserQuestionResolver r = new ScriptedUserQuestionResolver("a", "b");
            r.ask(UserQuestion.freeform("first?"));
            r.ask(UserQuestion.freeform("second?"));
            List<UserQuestion> history = r.askedHistory();
            assertEquals(2, history.size());
            assertEquals("first?", history.get(0).question());
            assertEquals("second?", history.get(1).question());
        }
    }

    // =================================================================
    // RecordingUserQuestionResolver
    // =================================================================
    @Nested
    @DisplayName("RecordingUserQuestionResolver")
    class RecordingResolver {

        @Test
        void recordsQuestionAndAnswer() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("staging");
            RecordingUserQuestionResolver rec = new RecordingUserQuestionResolver(inner);

            rec.ask(UserQuestion.choose("env?", List.of("staging", "production")));

            assertEquals(1, rec.history().size());
            assertEquals("staging", rec.receivedAnswers().get(0).value());
            assertEquals(1, rec.askedQuestions().size());
        }

        @Test
        void recordsOrderAcrossMultipleQuestions() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver(
                    "yes", "no", "maybe");
            RecordingUserQuestionResolver rec = new RecordingUserQuestionResolver(inner);

            rec.ask(UserQuestion.freeform("first?"));
            rec.ask(UserQuestion.freeform("second?"));
            rec.ask(UserQuestion.freeform("third?"));

            List<RecordingUserQuestionResolver.Entry> hist = rec.history();
            assertEquals("first?", hist.get(0).question().question());
            assertEquals("second?", hist.get(1).question().question());
            assertEquals("third?", hist.get(2).question().question());

            List<UserAnswer> answers = rec.receivedAnswers();
            assertEquals("yes", answers.get(0).value());
            assertEquals("no", answers.get(1).value());
            assertEquals("maybe", answers.get(2).value());
        }

        @Test
        void unansweredQuestionsCapturedOnFailure() {
            UserQuestionResolver throwing = q -> {
                throw new UserQuestionResolver.UserQuestionException("simulated failure");
            };
            RecordingUserQuestionResolver rec = new RecordingUserQuestionResolver(throwing);

            UserQuestion q = UserQuestion.freeform("anything?");
            assertThrows(UserQuestionResolver.UserQuestionException.class, () -> rec.ask(q));

            assertEquals(1, rec.unansweredQuestions().size());
            assertEquals(q.id(), rec.unansweredQuestions().get(0).id());
            assertTrue(rec.receivedAnswers().isEmpty());
        }

        @Test
        void historyIsImmutableSnapshot() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("ok");
            RecordingUserQuestionResolver rec = new RecordingUserQuestionResolver(inner);
            rec.ask(UserQuestion.freeform("q?"));

            List<RecordingUserQuestionResolver.Entry> hist = rec.history();
            assertThrows(UnsupportedOperationException.class,
                    () -> hist.add(null));
        }
    }

    // =================================================================
    // AskUserQuestionTool — agent-callable ToolCallback
    // =================================================================
    @Nested
    @DisplayName("AskUserQuestionTool")
    class Tool {

        @Test
        void handleReturnsSelectedOptionMessage() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("staging");
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "Which env?";
            input.options = List.of("staging", "production");
            input.allowFreeform = false;

            String result = AskUserQuestionTool.handle(inner, input);
            assertEquals("User selected: staging", result);
        }

        @Test
        void handleReturnsFreeformMessage() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("a long custom answer");
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "What do you want?";
            input.options = null;
            input.allowFreeform = true;

            String result = AskUserQuestionTool.handle(inner, input);
            assertEquals("User answered (freeform): a long custom answer", result);
        }

        @Test
        void handleRejectsBlankQuestion() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("anything");
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "  ";

            String result = AskUserQuestionTool.handle(inner, input);
            assertTrue(result.startsWith("Error:"), result);
            assertEquals(1, inner.remaining(), "no scripted answer should have been consumed");
        }

        @Test
        void handleSurfacesResolverExceptionAsErrorString() {
            UserQuestionResolver throwing = q -> {
                throw new UserQuestionResolver.UserQuestionException("user cancelled");
            };
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "anything?";

            String result = AskUserQuestionTool.handle(throwing, input);
            assertEquals("Error: user cancelled", result);
        }

        @Test
        void handleSurfacesUnexpectedExceptionAsErrorString() {
            UserQuestionResolver throwing = q -> { throw new IllegalStateException("oops"); };
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "anything?";

            String result = AskUserQuestionTool.handle(throwing, input);
            assertTrue(result.startsWith("Error: IllegalStateException"), result);
        }

        @Test
        void allowFreeformDefaultsToTrueWhenOptionsEmpty() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("anything goes");
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "tell me something";
            input.options = null;
            input.allowFreeform = null;  // unspecified

            String result = AskUserQuestionTool.handle(inner, input);
            assertEquals("User answered (freeform): anything goes", result);
        }

        @Test
        void allowFreeformDefaultsToFalseWhenOptionsProvided() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver("staging");
            AskUserQuestionTool.AskUserQuestionInput input = new AskUserQuestionTool.AskUserQuestionInput();
            input.question = "env?";
            input.options = List.of("staging", "production");
            input.allowFreeform = null;  // unspecified -> defaults to false

            String result = AskUserQuestionTool.handle(inner, input);
            assertEquals("User selected: staging", result);
        }

        @Test
        void buildCallbackHasExpectedFunctionName() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver();
            assertEquals("ask_user_question",
                    AskUserQuestionTool.callback(inner).getToolDefinition().name());
        }

        @Test
        void buildCallbackAcceptsCustomFunctionName() {
            ScriptedUserQuestionResolver inner = new ScriptedUserQuestionResolver();
            assertEquals("ask_human",
                    AskUserQuestionTool.callback(inner, "ask_human").getToolDefinition().name());
        }
    }
}
