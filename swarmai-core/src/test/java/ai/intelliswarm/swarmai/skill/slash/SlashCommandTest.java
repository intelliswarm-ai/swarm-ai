package ai.intelliswarm.swarmai.skill.slash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the slash-command harness primitive. */
class SlashCommandTest {

    @Nested
    @DisplayName("SlashSkill record")
    class SkillRecord {

        @Test
        void simpleSkillExecutesAction() {
            SlashSkill s = new SlashSkill("echo", "echo back the args",
                    args -> "got: " + args);
            assertEquals("got: hello", s.execute("hello"));
        }

        @Test
        void nullArgsTreatedAsEmpty() {
            SlashSkill s = new SlashSkill("echo", "echo args",
                    args -> "[" + args + "]");
            assertEquals("[]", s.execute(null));
        }

        @Test
        void blankNameRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SlashSkill("", "desc", a -> ""));
        }

        @Test
        void nameWithUppercaseRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SlashSkill("Help", "desc", a -> ""));
        }

        @Test
        void nameWithSpaceRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SlashSkill("foo bar", "desc", a -> ""));
        }

        @Test
        void hyphensAndUnderscoresAndDigitsAllowed() {
            assertDoesNotThrow(() ->
                    new SlashSkill("foo-bar_2", "desc", a -> ""));
        }

        @Test
        void blankDescriptionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SlashSkill("foo", "  ", a -> ""));
        }

        @Test
        void nullActionRejected() {
            assertThrows(NullPointerException.class,
                    () -> new SlashSkill("foo", "desc", null));
        }

        @Test
        void isValidNameLogic() {
            assertTrue(SlashSkill.isValidName("a"));
            assertTrue(SlashSkill.isValidName("foo_bar"));
            assertTrue(SlashSkill.isValidName("v2"));
            assertTrue(SlashSkill.isValidName("foo-bar"));

            assertFalse(SlashSkill.isValidName(""));
            assertFalse(SlashSkill.isValidName(null));
            assertFalse(SlashSkill.isValidName("Foo"));
            assertFalse(SlashSkill.isValidName("foo bar"));
            assertFalse(SlashSkill.isValidName("foo!"));
            assertFalse(SlashSkill.isValidName("/foo"));
        }
    }

    @Nested
    @DisplayName("SlashCommand.parse")
    class Parsing {

        @Test
        void parsesNameWithNoArgs() {
            Optional<SlashCommand> r = SlashCommand.parse("/help");
            assertTrue(r.isPresent());
            assertEquals("help", r.get().name());
            assertEquals("", r.get().args());
            assertFalse(r.get().hasArgs());
        }

        @Test
        void parsesNameWithSingleArg() {
            Optional<SlashCommand> r = SlashCommand.parse("/summarise hello world");
            assertTrue(r.isPresent());
            assertEquals("summarise", r.get().name());
            assertEquals("hello world", r.get().args());
            assertTrue(r.get().hasArgs());
        }

        @Test
        void parsesNameWithMultilineArgs() {
            Optional<SlashCommand> r = SlashCommand.parse("/summarise line1\nline2\nline3");
            assertTrue(r.isPresent());
            assertEquals("line1\nline2\nline3", r.get().args());
        }

        @Test
        void leadingWhitespaceTolerated() {
            Optional<SlashCommand> r = SlashCommand.parse("   /help");
            assertTrue(r.isPresent());
            assertEquals("help", r.get().name());
        }

        @Test
        void plainTextReturnsEmpty() {
            assertTrue(SlashCommand.parse("hello world").isEmpty());
            assertTrue(SlashCommand.parse("").isEmpty());
            assertTrue(SlashCommand.parse("   ").isEmpty());
        }

        @Test
        void nullReturnsEmpty() {
            assertTrue(SlashCommand.parse(null).isEmpty());
        }

        @Test
        void slashAloneIsNotACommand() {
            // Just `/` doesn't match the [a-z0-9_-]+ name token
            assertTrue(SlashCommand.parse("/").isEmpty());
            assertTrue(SlashCommand.parse("/   ").isEmpty());
        }

        @Test
        void invalidNameGrammarReturnsEmpty() {
            // Uppercase, special chars — pass through to LLM
            assertTrue(SlashCommand.parse("/Help").isEmpty());
            assertTrue(SlashCommand.parse("/foo!").isEmpty());
            assertTrue(SlashCommand.parse("/path/to/file").isEmpty());
        }

        @Test
        void rawIsPreservedForLogging() {
            String input = "  /help  with spaces  ";
            Optional<SlashCommand> r = SlashCommand.parse(input);
            assertTrue(r.isPresent());
            assertEquals(input, r.get().raw());
        }

        @Test
        void singleSpaceArgSeparatorWorks() {
            // Tab separator also accepted
            assertEquals("hi",
                    SlashCommand.parse("/echo\thi").orElseThrow().args());
        }
    }

    @Nested
    @DisplayName("SlashSkillRegistry")
    class Registry {

        @Test
        void registerAndLookup() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            SlashSkill s = new SlashSkill("foo", "desc", a -> "x");
            reg.register(s);

            assertSame(s, reg.lookup("foo").orElseThrow());
            assertTrue(reg.contains("foo"));
            assertEquals(1, reg.size());
            assertFalse(reg.isEmpty());
        }

        @Test
        void lookupUnknownReturnsEmpty() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            assertTrue(reg.lookup("anything").isEmpty());
            assertFalse(reg.contains("anything"));
        }

        @Test
        void registerReplacesPriorEntry() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("foo", "first", a -> "v1"));
            reg.register(new SlashSkill("foo", "second", a -> "v2"));
            assertEquals("v2", reg.lookup("foo").orElseThrow().execute(""));
            assertEquals(1, reg.size());
        }

        @Test
        void registerStrictFailsOnDuplicate() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.registerStrict(new SlashSkill("foo", "first", a -> ""));
            assertThrows(IllegalArgumentException.class,
                    () -> reg.registerStrict(new SlashSkill("foo", "second", a -> "")));
        }

        @Test
        void unregisterRemoves() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("foo", "desc", a -> ""));
            assertTrue(reg.unregister("foo").isPresent());
            assertFalse(reg.contains("foo"));
            assertTrue(reg.unregister("foo").isEmpty(), "second unregister returns empty");
        }

        @Test
        void allReturnsRegistrationOrder() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("c", "desc", a -> ""));
            reg.register(new SlashSkill("a", "desc", a -> ""));
            reg.register(new SlashSkill("b", "desc", a -> ""));

            var names = reg.all().stream().map(SlashSkill::name).toList();
            assertEquals(java.util.List.of("c", "a", "b"), names);
        }

        @Test
        void allIsImmutableSnapshot() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("foo", "desc", a -> ""));
            assertThrows(UnsupportedOperationException.class,
                    () -> reg.all().clear());
        }

        @Test
        void emptyRegistryReports() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            assertTrue(reg.isEmpty());
            assertEquals(0, reg.size());
        }
    }

    @Nested
    @DisplayName("SlashCommandRouter")
    class Router {

        @Test
        void plainTextPassesThrough() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            SlashCommandRouter router = new SlashCommandRouter(reg);

            RouterResult r = router.route("hello, agent");
            assertInstanceOf(RouterResult.PassThrough.class, r);
            assertEquals("hello, agent",
                    ((RouterResult.PassThrough) r).input());
        }

        @Test
        void registeredSlashCommandExecutes() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("echo", "echo args",
                    args -> "you said: " + args));
            SlashCommandRouter router = new SlashCommandRouter(reg);

            RouterResult r = router.route("/echo hello world");
            assertInstanceOf(RouterResult.SkillExecuted.class, r);
            RouterResult.SkillExecuted e = (RouterResult.SkillExecuted) r;
            assertEquals("echo", e.skillName());
            assertEquals("you said: hello world", e.output());
        }

        @Test
        void unknownSlashCommandReturnsNotFound() {
            SlashCommandRouter router = new SlashCommandRouter(new SlashSkillRegistry());
            RouterResult r = router.route("/nonexistent some args");
            assertInstanceOf(RouterResult.SkillNotFound.class, r);
            assertEquals("nonexistent",
                    ((RouterResult.SkillNotFound) r).name());
        }

        @Test
        void invalidSlashGrammarPassesThrough() {
            SlashCommandRouter router = new SlashCommandRouter(new SlashSkillRegistry());
            // /Help has uppercase — invalid grammar; router treats as plain text
            RouterResult r = router.route("/Help");
            assertInstanceOf(RouterResult.PassThrough.class, r);
        }

        @Test
        void skillThrowsPropagates() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("boom", "throws",
                    args -> { throw new IllegalStateException("boom"); }));
            SlashCommandRouter router = new SlashCommandRouter(reg);

            assertThrows(IllegalStateException.class, () -> router.route("/boom"));
        }

        @Test
        void skillReceivesEmptyArgsWhenNoneTyped() {
            AtomicReference<String> received = new AtomicReference<>();
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("ping", "desc", args -> {
                received.set(args);
                return "pong";
            }));
            SlashCommandRouter router = new SlashCommandRouter(reg);

            router.route("/ping");
            assertEquals("", received.get());
        }

        @Test
        void nullInputPassesThrough() {
            SlashCommandRouter router = new SlashCommandRouter(new SlashSkillRegistry());
            RouterResult r = router.route(null);
            assertInstanceOf(RouterResult.PassThrough.class, r);
            assertEquals("", ((RouterResult.PassThrough) r).input());
        }

        @Test
        void registryAccessor() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            SlashCommandRouter router = new SlashCommandRouter(reg);
            assertSame(reg, router.registry());
        }
    }

    @Nested
    @DisplayName("HelpSkill")
    class Help {

        @Test
        void emptyRegistryRendersFallback() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            assertEquals("No commands are registered.", HelpSkill.render(reg));
        }

        @Test
        void singleSkillRenders() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("ping", "send a ping", a -> "pong"));
            String out = HelpSkill.render(reg);
            assertTrue(out.startsWith("Available commands:\n"));
            assertTrue(out.contains("/ping"));
            assertTrue(out.contains("send a ping"));
        }

        @Test
        void preservesRegistrationOrder() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("c", "third", a -> ""));
            reg.register(new SlashSkill("a", "first", a -> ""));
            reg.register(new SlashSkill("b", "second", a -> ""));

            String out = HelpSkill.render(reg);
            int posC = out.indexOf("/c ");
            int posA = out.indexOf("/a ");
            int posB = out.indexOf("/b ");
            assertTrue(posC < posA && posA < posB,
                    "expected order c < a < b: " + out);
        }

        @Test
        void factoryReturnsSlashSkillRunningRender() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("ping", "desc", a -> ""));
            SlashSkill help = HelpSkill.of(reg);

            assertEquals("help", help.name());
            assertEquals("list available commands", help.description());
            String out = help.execute("");
            assertTrue(out.contains("/ping"));
        }

        @Test
        void factoryAcceptsCustomName() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            assertEquals("commands", HelpSkill.of(reg, "commands").name());
        }

        @Test
        void selfRegistrationListsHelpItself() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            SlashSkill help = HelpSkill.of(reg);
            reg.register(help);
            String out = help.execute("");
            assertTrue(out.contains("/help"),
                    "help skill should list itself when registered: " + out);
        }
    }

    // =================================================================
    // End-to-end: build a small registry, route inputs, verify results.
    // =================================================================
    @Nested
    @DisplayName("end-to-end")
    class EndToEnd {

        @Test
        void mixedInputsRouteCorrectly() {
            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("upper", "uppercase",
                    args -> args.toUpperCase()));
            reg.register(HelpSkill.of(reg));

            SlashCommandRouter router = new SlashCommandRouter(reg);

            // Plain text → passthrough
            RouterResult plain = router.route("hello world");
            assertInstanceOf(RouterResult.PassThrough.class, plain);

            // Known slash → executes
            RouterResult upper = router.route("/upper hello");
            assertEquals("HELLO",
                    ((RouterResult.SkillExecuted) upper).output());

            // /help → lists everything including itself
            RouterResult help = router.route("/help");
            String helpOut = ((RouterResult.SkillExecuted) help).output();
            assertTrue(helpOut.contains("/upper"));
            assertTrue(helpOut.contains("/help"));

            // Unknown → notFound
            RouterResult missing = router.route("/nonexistent");
            assertInstanceOf(RouterResult.SkillNotFound.class, missing);
        }

        @Test
        void skillsCanCaptureExternalState() {
            // Demonstrates: a skill action can close over harness state — the
            // exact pattern an LLM-backed skill uses to capture a ChatClient.
            int[] callCount = {0};
            Function<String, String> counter = args -> {
                callCount[0]++;
                return "count=" + callCount[0];
            };

            SlashSkillRegistry reg = new SlashSkillRegistry();
            reg.register(new SlashSkill("count", "increment counter", counter));
            SlashCommandRouter router = new SlashCommandRouter(reg);

            assertEquals("count=1",
                    ((RouterResult.SkillExecuted) router.route("/count")).output());
            assertEquals("count=2",
                    ((RouterResult.SkillExecuted) router.route("/count")).output());
            assertEquals("count=3",
                    ((RouterResult.SkillExecuted) router.route("/count")).output());
        }
    }
}
