package ai.intelliswarm.swarmai.message.typed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the TypedMessageHistory primitive (1.0.19+). */
class TypedMessageHistoryTest {

    @Nested
    @DisplayName("ToolCall record")
    class ToolCallRecord {

        @Test
        void simpleConstructionWorks() {
            ToolCall c = new ToolCall("call-1", "calculator", "{\"expression\":\"2+2\"}");
            assertEquals("call-1", c.id());
            assertEquals("calculator", c.name());
            assertEquals("{\"expression\":\"2+2\"}", c.argsJson());
        }

        @Test
        void ofFactoryUsesEmptyJson() {
            ToolCall c = ToolCall.of("call-1", "current_time");
            assertEquals("{}", c.argsJson());
        }

        @Test
        void blankIdRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolCall("", "name", "{}"));
        }

        @Test
        void blankNameRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolCall("id", "", "{}"));
        }

        @Test
        void nullsRejected() {
            assertThrows(NullPointerException.class,
                    () -> new ToolCall(null, "n", "{}"));
            assertThrows(NullPointerException.class,
                    () -> new ToolCall("id", null, "{}"));
            assertThrows(NullPointerException.class,
                    () -> new ToolCall("id", "n", null));
        }
    }

    @Nested
    @DisplayName("Typed message records")
    class MessageRecords {

        @Test
        void typedSystemMessageNowFactory() {
            TypedSystemMessage m = TypedSystemMessage.now("you are a helpful agent");
            assertEquals("you are a helpful agent", m.content());
            assertNotNull(m.timestamp());
            assertTrue(m.metadata().isEmpty());
        }

        @Test
        void typedUserMessageNowFactory() {
            TypedUserMessage m = TypedUserMessage.now("hello");
            assertEquals("hello", m.content());
            assertNotNull(m.timestamp());
        }

        @Test
        void typedAssistantMessageNowFactory() {
            TypedAssistantMessage m = TypedAssistantMessage.now("hi back");
            assertEquals("hi back", m.content());
            assertTrue(m.toolCalls().isEmpty());
            assertFalse(m.hasToolCalls());
        }

        @Test
        void typedAssistantMessageWithCalls() {
            ToolCall c = ToolCall.of("c1", "ping");
            TypedAssistantMessage m = TypedAssistantMessage.withCalls("calling tool", List.of(c));
            assertTrue(m.hasToolCalls());
            assertEquals(1, m.toolCalls().size());
            assertEquals("ping", m.toolCalls().get(0).name());
        }

        @Test
        void toolCallsAreImmutable() {
            ToolCall c = ToolCall.of("c1", "ping");
            TypedAssistantMessage m = TypedAssistantMessage.withCalls("x", List.of(c));
            assertThrows(UnsupportedOperationException.class,
                    () -> m.toolCalls().add(ToolCall.of("c2", "pong")));
        }

        @Test
        void metadataIsImmutable() {
            TypedUserMessage m = new TypedUserMessage("hi", Instant.now(),
                    Map.of("trace", "abc"));
            assertThrows(UnsupportedOperationException.class,
                    () -> m.metadata().put("trace", "xyz"));
        }

        @Test
        void typedToolCallMessageNowFactory() {
            ToolCall c = ToolCall.of("c1", "ping");
            TypedToolCallMessage m = TypedToolCallMessage.now(c);
            assertSame(c, m.toolCall());
            assertTrue(m.content().startsWith("ping("));
        }

        @Test
        void typedToolResultMessageSuccessAndError() {
            TypedToolResultMessage ok = TypedToolResultMessage.success("c1", "42");
            assertFalse(ok.isError());
            assertEquals("42", ok.content());
            assertEquals("c1", ok.toolCallId());

            TypedToolResultMessage err = TypedToolResultMessage.error("c1", "failed");
            assertTrue(err.isError());
        }

        @Test
        void blankToolCallIdInResultRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TypedToolResultMessage("x", Instant.now(), "  ", false, Map.of()));
        }

        @Test
        void nullContentRejectedAcrossAllRecords() {
            assertThrows(NullPointerException.class,
                    () -> new TypedSystemMessage(null, Instant.now(), Map.of()));
            assertThrows(NullPointerException.class,
                    () -> new TypedUserMessage(null, Instant.now(), Map.of()));
            assertThrows(NullPointerException.class,
                    () -> new TypedAssistantMessage(null, Instant.now(), List.of(), Map.of()));
            assertThrows(NullPointerException.class,
                    () -> new TypedToolCallMessage(null, Instant.now(), ToolCall.of("x", "y"), Map.of()));
            assertThrows(NullPointerException.class,
                    () -> new TypedToolResultMessage(null, Instant.now(), "x", false, Map.of()));
        }
    }

    @Nested
    @DisplayName("history container")
    class HistoryBasics {

        @Test
        void emptyHistoryQueriesReturnEmpty() {
            TypedMessageHistory h = new TypedMessageHistory();
            assertEquals(0, h.size());
            assertTrue(h.isEmpty());
            assertTrue(h.all().isEmpty());
            assertTrue(h.userMessages().isEmpty());
            assertTrue(h.firstUserMessage().isEmpty());
            assertTrue(h.lastAssistantMessage().isEmpty());
            assertEquals(0, h.toolCallCount());
            assertEquals(0, h.toolResultCount());
        }

        @Test
        void addAppendsInOrder() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedUserMessage.now("a"));
            h.add(TypedUserMessage.now("b"));
            h.add(TypedUserMessage.now("c"));
            List<TypedMessage> all = h.all();
            assertEquals(3, all.size());
            assertEquals("a", ((TypedUserMessage) all.get(0)).content());
            assertEquals("b", ((TypedUserMessage) all.get(1)).content());
            assertEquals("c", ((TypedUserMessage) all.get(2)).content());
        }

        @Test
        void addAllAppends() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.addAll(List.of(
                    TypedSystemMessage.now("sys"),
                    TypedUserMessage.now("u")));
            assertEquals(2, h.size());
        }

        @Test
        void allReturnsImmutableSnapshot() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedUserMessage.now("a"));
            assertThrows(UnsupportedOperationException.class,
                    () -> h.all().add(TypedUserMessage.now("b")));
        }

        @Test
        void clearEmpties() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedUserMessage.now("a"));
            h.add(TypedUserMessage.now("b"));
            h.clear();
            assertTrue(h.isEmpty());
        }

        @Test
        void preBuiltConstructorIsCopying() {
            List<TypedMessage> initial = new java.util.ArrayList<>();
            initial.add(TypedUserMessage.now("a"));
            TypedMessageHistory h = new TypedMessageHistory(initial);
            initial.add(TypedUserMessage.now("b"));  // mutate input
            assertEquals(1, h.size(), "history must not see mutations to its input list");
        }

        @Test
        void nullMessageRejected() {
            TypedMessageHistory h = new TypedMessageHistory();
            assertThrows(NullPointerException.class, () -> h.add(null));
        }
    }

    @Nested
    @DisplayName("type-specific queries")
    class TypeQueries {

        private TypedMessageHistory mixed() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedSystemMessage.now("sys-1"));
            h.add(TypedUserMessage.now("u-1"));
            h.add(TypedAssistantMessage.withCalls("planning",
                    List.of(ToolCall.of("c1", "search"))));
            h.add(TypedToolResultMessage.success("c1", "result-1"));
            h.add(TypedAssistantMessage.now("done"));
            h.add(TypedUserMessage.now("u-2"));
            h.add(TypedToolCallMessage.now(ToolCall.of("c2", "ping")));
            h.add(TypedToolResultMessage.error("c2", "boom"));
            return h;
        }

        @Test
        void systemMessagesReturnsOnlySystem() {
            TypedMessageHistory h = mixed();
            assertEquals(1, h.systemMessages().size());
            assertEquals("sys-1", h.systemMessages().get(0).content());
        }

        @Test
        void userMessagesReturnsOnlyUsers() {
            TypedMessageHistory h = mixed();
            List<TypedUserMessage> users = h.userMessages();
            assertEquals(2, users.size());
            assertEquals("u-1", users.get(0).content());
            assertEquals("u-2", users.get(1).content());
        }

        @Test
        void assistantMessagesReturnsOnlyAssistants() {
            TypedMessageHistory h = mixed();
            List<TypedAssistantMessage> a = h.assistantMessages();
            assertEquals(2, a.size());
        }

        @Test
        void toolResultMessagesIncludesBothOkAndError() {
            TypedMessageHistory h = mixed();
            assertEquals(2, h.toolResultMessages().size());
        }

        @Test
        void toolCallMessagesReturnsOnlyStandalone() {
            TypedMessageHistory h = mixed();
            // Only the explicit TypedToolCallMessage entry (c2) — calls embedded
            // in TypedAssistantMessage don't count here.
            assertEquals(1, h.toolCallMessages().size());
        }

        @Test
        void firstUserMessageReturnsFirst() {
            TypedMessageHistory h = mixed();
            assertEquals("u-1", h.firstUserMessage().orElseThrow().content());
        }

        @Test
        void lastAssistantMessageReturnsMostRecent() {
            TypedMessageHistory h = mixed();
            assertEquals("done", h.lastAssistantMessage().orElseThrow().content());
        }

        @Test
        void lastMessageReturnsTail() {
            TypedMessageHistory h = mixed();
            TypedMessage last = h.lastMessage().orElseThrow();
            assertInstanceOf(TypedToolResultMessage.class, last);
            assertTrue(((TypedToolResultMessage) last).isError());
        }

        @Test
        void toolCallCountSumsBothSources() {
            TypedMessageHistory h = mixed();
            // 1 embedded in assistant + 1 standalone tool-call message = 2
            assertEquals(2, h.toolCallCount());
        }

        @Test
        void toolResultCountAndErrorCountSeparate() {
            TypedMessageHistory h = mixed();
            assertEquals(2, h.toolResultCount());
            assertEquals(1, h.toolErrorCount());
        }

        @Test
        void filterTypeGenericForm() {
            TypedMessageHistory h = mixed();
            List<TypedSystemMessage> sys = h.filterType(TypedSystemMessage.class);
            assertEquals(1, sys.size());
        }

        @Test
        void filterPredicateGeneric() {
            TypedMessageHistory h = mixed();
            List<TypedMessage> errs = h.filter(m ->
                    m instanceof TypedToolResultMessage r && r.isError());
            assertEquals(1, errs.size());
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        void emptyHistoryRendersToEmptyString() {
            assertEquals("", new TypedMessageHistory().renderTranscript());
        }

        @Test
        void rendersAllRoles() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedSystemMessage.now("be helpful"));
            h.add(TypedUserMessage.now("hello"));
            h.add(TypedAssistantMessage.now("hi back"));

            String out = h.renderTranscript();
            assertTrue(out.contains("[system]"));
            assertTrue(out.contains("[user]"));
            assertTrue(out.contains("[assistant]"));
            assertTrue(out.contains("hello"));
            assertTrue(out.contains("hi back"));
        }

        @Test
        void rendersToolCallsInlineUnderAssistant() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedAssistantMessage.withCalls("planning",
                    List.of(ToolCall.of("c1", "search"))));
            String out = h.renderTranscript();
            assertTrue(out.contains("[assistant]"));
            assertTrue(out.contains("↳ tool_call search"));
            assertTrue(out.contains("#c1"));
        }

        @Test
        void distinguishesToolOkVsToolErr() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedToolResultMessage.success("c1", "ok"));
            h.add(TypedToolResultMessage.error("c2", "no"));
            String out = h.renderTranscript();
            assertTrue(out.contains("[tool_ok]"));
            assertTrue(out.contains("[tool_err]"));
        }

        @Test
        void multilineContentCollapsesToOneLineInTranscript() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedUserMessage.now("line1\nline2\nline3"));
            String out = h.renderTranscript();
            // Each rendered line contains the full one-line content
            String firstLine = out.split("\n", 2)[0];
            assertTrue(firstLine.contains("line1 line2 line3"),
                    "content should be flattened: " + firstLine);
        }
    }

    @Nested
    @DisplayName("Spring AI round-trip")
    class SpringAi {

        @Test
        void toSpringMessagesPreservesSystemUserAssistant() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedSystemMessage.now("sys"));
            h.add(TypedUserMessage.now("u"));
            h.add(TypedAssistantMessage.now("a"));

            List<Message> spring = h.toSpringMessages();
            assertEquals(3, spring.size());
            assertInstanceOf(SystemMessage.class, spring.get(0));
            assertInstanceOf(UserMessage.class, spring.get(1));
            assertInstanceOf(AssistantMessage.class, spring.get(2));
        }

        @Test
        void toSpringMessagesSkipsToolMessages() {
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedSystemMessage.now("sys"));
            h.add(TypedUserMessage.now("u"));
            h.add(TypedToolCallMessage.now(ToolCall.of("c1", "ping")));
            h.add(TypedToolResultMessage.success("c1", "pong"));
            h.add(TypedAssistantMessage.now("a"));

            // 3 Spring messages — tool call + result are skipped (Spring AI
            // re-runs the tool loop from scratch on replay)
            assertEquals(3, h.toSpringMessages().size());
        }

        @Test
        void fromSpringMessagesParsesAllSupportedTypes() {
            List<Message> spring = List.of(
                    new SystemMessage("sys"),
                    new UserMessage("u"),
                    new AssistantMessage("a"));
            TypedMessageHistory h = TypedMessageHistory.fromSpringMessages(spring);
            assertEquals(3, h.size());
            assertInstanceOf(TypedSystemMessage.class, h.all().get(0));
            assertInstanceOf(TypedUserMessage.class, h.all().get(1));
            assertInstanceOf(TypedAssistantMessage.class, h.all().get(2));
        }

        @Test
        void roundTripPreservesContent() {
            TypedMessageHistory original = new TypedMessageHistory();
            original.add(TypedSystemMessage.now("you are helpful"));
            original.add(TypedUserMessage.now("hi"));
            original.add(TypedAssistantMessage.now("hello!"));

            List<Message> spring = original.toSpringMessages();
            TypedMessageHistory parsed = TypedMessageHistory.fromSpringMessages(spring);

            assertEquals(3, parsed.size());
            assertEquals("you are helpful", parsed.systemMessages().get(0).content());
            assertEquals("hi", parsed.userMessages().get(0).content());
            assertEquals("hello!", parsed.assistantMessages().get(0).content());
        }

        @Test
        void fromSpringMessagesHandlesEmptyList() {
            assertEquals(0, TypedMessageHistory.fromSpringMessages(List.of()).size());
        }

        @Test
        void fromSpringMessagesNullRejected() {
            assertThrows(NullPointerException.class,
                    () -> TypedMessageHistory.fromSpringMessages(null));
        }
    }

    @Nested
    @DisplayName("exhaustive switch on the sealed hierarchy")
    class ExhaustiveSwitch {

        @Test
        void switchOverAllVariantsCompilesAndRuns() {
            // This test exists primarily as a compile-time check that the
            // sealed permits clause covers every variant we expect.
            TypedMessageHistory h = new TypedMessageHistory();
            h.add(TypedSystemMessage.now("sys"));
            h.add(TypedUserMessage.now("u"));
            h.add(TypedAssistantMessage.withCalls("plan",
                    List.of(ToolCall.of("c1", "x"))));
            h.add(TypedToolCallMessage.now(ToolCall.of("c2", "y")));
            h.add(TypedToolResultMessage.success("c2", "ok"));

            int sysCount = 0, userCount = 0, assistantCount = 0,
                callCount = 0, resultCount = 0;
            for (TypedMessage m : h.all()) {
                int variant = switch (m) {
                    case TypedSystemMessage ignored -> 1;
                    case TypedUserMessage ignored -> 2;
                    case TypedAssistantMessage ignored -> 3;
                    case TypedToolCallMessage ignored -> 4;
                    case TypedToolResultMessage ignored -> 5;
                };
                switch (variant) {
                    case 1 -> sysCount++;
                    case 2 -> userCount++;
                    case 3 -> assistantCount++;
                    case 4 -> callCount++;
                    case 5 -> resultCount++;
                }
            }
            assertEquals(1, sysCount);
            assertEquals(1, userCount);
            assertEquals(1, assistantCount);
            assertEquals(1, callCount);
            assertEquals(1, resultCount);
        }
    }
}
