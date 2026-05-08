package ai.intelliswarm.swarmai.message.typed;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * In-memory, append-only log of {@link TypedMessage}s with type-aware queries.
 * The Pydantic-style counterpart to a flat {@code List<Message>} — same
 * underlying linearity, but exhaustive switch + targeted accessors instead of
 * scattered {@code instanceof} chains.
 *
 * <h2>Threading</h2>
 * Methods are synchronized — {@link #add} is the typical hot path during
 * tool-callback execution and may run on any thread.
 *
 * <h2>Round-trip with Spring AI</h2>
 * <ul>
 *   <li>{@link #toSpringMessages()} converts the typed history back into a
 *       list compatible with {@code chatClient.prompt().messages(...)}. Tool
 *       calls and tool results are folded into the produced
 *       {@code AssistantMessage} / user-role tool message conventions.</li>
 *   <li>{@link #fromSpringMessages(List)} parses an existing Spring AI history
 *       into typed records. Lossy: Spring AI tool messages don't always
 *       preserve {@code toolCallId} structurally, so freshly-typed histories
 *       are richer than ones round-tripped from Spring AI.</li>
 * </ul>
 */
public final class TypedMessageHistory {

    private final List<TypedMessage> messages =
            Collections.synchronizedList(new ArrayList<>());

    /** Empty history. */
    public TypedMessageHistory() {}

    /** Pre-populated history. */
    public TypedMessageHistory(List<TypedMessage> initial) {
        if (initial != null) {
            for (TypedMessage m : initial) {
                Objects.requireNonNull(m, "history entry");
                this.messages.add(m);
            }
        }
    }

    /** Append a message to the history. */
    public void add(TypedMessage message) {
        Objects.requireNonNull(message, "message");
        messages.add(message);
    }

    /** Append all messages. */
    public void addAll(List<? extends TypedMessage> batch) {
        Objects.requireNonNull(batch, "batch");
        for (TypedMessage m : batch) add(m);
    }

    /** Snapshot of every message, in order. Immutable. */
    public List<TypedMessage> all() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /** Number of messages. */
    public int size() {
        synchronized (messages) {
            return messages.size();
        }
    }

    /** True if no messages have been added. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /** Drop everything. */
    public void clear() {
        synchronized (messages) {
            messages.clear();
        }
    }

    // =================================================================
    // Type-specific queries — return only matching variants
    // =================================================================

    public List<TypedSystemMessage> systemMessages() {
        return filterType(TypedSystemMessage.class);
    }

    public List<TypedUserMessage> userMessages() {
        return filterType(TypedUserMessage.class);
    }

    public List<TypedAssistantMessage> assistantMessages() {
        return filterType(TypedAssistantMessage.class);
    }

    public List<TypedToolCallMessage> toolCallMessages() {
        return filterType(TypedToolCallMessage.class);
    }

    public List<TypedToolResultMessage> toolResultMessages() {
        return filterType(TypedToolResultMessage.class);
    }

    /** Generic filter by concrete subtype. */
    @SuppressWarnings("unchecked")
    public <T extends TypedMessage> List<T> filterType(Class<T> type) {
        Objects.requireNonNull(type, "type");
        List<T> out = new ArrayList<>();
        synchronized (messages) {
            for (TypedMessage m : messages) {
                if (type.isInstance(m)) out.add((T) m);
            }
        }
        return List.copyOf(out);
    }

    /** Generic predicate filter. */
    public List<TypedMessage> filter(Predicate<TypedMessage> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<TypedMessage> out = new ArrayList<>();
        synchronized (messages) {
            for (TypedMessage m : messages) {
                if (predicate.test(m)) out.add(m);
            }
        }
        return List.copyOf(out);
    }

    // =================================================================
    // Convenience accessors
    // =================================================================

    /** First user message, if any. */
    public Optional<TypedUserMessage> firstUserMessage() {
        synchronized (messages) {
            for (TypedMessage m : messages) {
                if (m instanceof TypedUserMessage u) return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    /** Most recent assistant message, if any. */
    public Optional<TypedAssistantMessage> lastAssistantMessage() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof TypedAssistantMessage a) return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** Most recent message of any type. */
    public Optional<TypedMessage> lastMessage() {
        synchronized (messages) {
            return messages.isEmpty() ? Optional.empty()
                    : Optional.of(messages.get(messages.size() - 1));
        }
    }

    /** Total tool calls across all assistant messages and standalone tool-call messages. */
    public int toolCallCount() {
        int total = 0;
        synchronized (messages) {
            for (TypedMessage m : messages) {
                switch (m) {
                    case TypedAssistantMessage a -> total += a.toolCalls().size();
                    case TypedToolCallMessage ignored -> total += 1;
                    default -> { /* no-op */ }
                }
            }
        }
        return total;
    }

    /** Number of tool results recorded. */
    public int toolResultCount() {
        int total = 0;
        synchronized (messages) {
            for (TypedMessage m : messages) {
                if (m instanceof TypedToolResultMessage) total++;
            }
        }
        return total;
    }

    /** Number of tool errors (results with isError=true). */
    public int toolErrorCount() {
        int total = 0;
        synchronized (messages) {
            for (TypedMessage m : messages) {
                if (m instanceof TypedToolResultMessage r && r.isError()) total++;
            }
        }
        return total;
    }

    // =================================================================
    // Rendering
    // =================================================================

    /**
     * Human-readable transcript of the conversation. Each message gets a
     * role-prefixed line; tool calls and results are inlined under their
     * assistant turn for readability.
     */
    public String renderTranscript() {
        StringBuilder sb = new StringBuilder();
        synchronized (messages) {
            for (TypedMessage m : messages) {
                appendMessage(sb, m);
            }
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static void appendMessage(StringBuilder sb, TypedMessage m) {
        switch (m) {
            case TypedSystemMessage s -> {
                sb.append("[system]    ").append(oneLine(s.content())).append('\n');
            }
            case TypedUserMessage u -> {
                sb.append("[user]      ").append(oneLine(u.content())).append('\n');
            }
            case TypedAssistantMessage a -> {
                sb.append("[assistant] ").append(oneLine(a.content())).append('\n');
                for (ToolCall c : a.toolCalls()) {
                    sb.append("            ↳ tool_call ").append(c.name())
                            .append(" #").append(c.id())
                            .append(" args=").append(c.argsJson()).append('\n');
                }
            }
            case TypedToolCallMessage c -> {
                sb.append("[tool_call] ").append(c.toolCall().name())
                        .append(" #").append(c.toolCall().id())
                        .append(" args=").append(c.toolCall().argsJson()).append('\n');
            }
            case TypedToolResultMessage r -> {
                sb.append(r.isError() ? "[tool_err]  " : "[tool_ok]   ")
                        .append("#").append(r.toolCallId()).append("  ")
                        .append(oneLine(r.content())).append('\n');
            }
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // =================================================================
    // Spring AI round-trip
    // =================================================================

    /**
     * Convert to a Spring AI message list ready for
     * {@code chatClient.prompt().messages(...)}. Tool-call and tool-result
     * messages are skipped (Spring AI's manual {@code messages(...)} path
     * doesn't have first-class support for them — they would be replayed by
     * the auto-tool loop). System / user / assistant survive.
     */
    public List<Message> toSpringMessages() {
        List<Message> out = new ArrayList<>();
        synchronized (messages) {
            for (TypedMessage m : messages) {
                switch (m) {
                    case TypedSystemMessage s -> out.add(new SystemMessage(s.content()));
                    case TypedUserMessage u -> out.add(new UserMessage(u.content()));
                    case TypedAssistantMessage a -> out.add(new AssistantMessage(a.content()));
                    case TypedToolCallMessage ignored -> { /* skip — re-run via tool loop */ }
                    case TypedToolResultMessage ignored -> { /* skip — re-run via tool loop */ }
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Best-effort import of a Spring AI message list. Loses tool-call /
     * tool-result granularity (Spring AI's {@code Message} interface doesn't
     * expose enough structure across versions), but preserves system / user /
     * assistant text. Use {@link #add} after import to layer in tool calls
     * captured from your own callbacks.
     */
    public static TypedMessageHistory fromSpringMessages(List<Message> springMessages) {
        Objects.requireNonNull(springMessages, "springMessages");
        TypedMessageHistory h = new TypedMessageHistory();
        for (Message m : springMessages) {
            TypedMessage typed = fromSpringMessage(m);
            if (typed != null) h.add(typed);
        }
        return h;
    }

    /** Convert a single Spring AI message to a typed record, or null if the type isn't recognised. */
    public static TypedMessage fromSpringMessage(Message m) {
        if (m instanceof SystemMessage s) {
            return TypedSystemMessage.now(s.getText());
        }
        if (m instanceof UserMessage u) {
            return TypedUserMessage.now(u.getText());
        }
        if (m instanceof AssistantMessage a) {
            return TypedAssistantMessage.now(a.getText() == null ? "" : a.getText());
        }
        return null;
    }
}
