package ai.intelliswarm.swarmai.state.reminder;

import java.util.List;
import java.util.Objects;

/**
 * Bridges a {@link SystemReminderChannel} into the agent's prompt-assembly path.
 * Provided as a small helper so examples and harness code don't reimplement the
 * drain-render-prepend dance against {@code ChatClient.prompt(...)}.
 *
 * <p>Typical use, before each LLM call:
 * <pre>{@code
 * String userMessage = decorator.prepend(rawUserInput);
 * chatClient.prompt().system(systemPrompt).user(userMessage).call().content();
 * }</pre>
 *
 * <p>{@link #prepend} drains the channel — pending reminders fire exactly once
 * across the conversation, matching standard harness drain-on-read semantics.
 */
public final class SystemReminderPromptDecorator {

    private final SystemReminderChannel channel;
    private final SystemReminderRenderer renderer;

    public SystemReminderPromptDecorator(SystemReminderChannel channel) {
        this(channel, new SystemReminderRenderer());
    }

    public SystemReminderPromptDecorator(SystemReminderChannel channel,
                                          SystemReminderRenderer renderer) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Drain the channel and render the reminders as a single block.
     * Returns the empty string when nothing was pending.
     */
    public String drainAndRender() {
        List<SystemReminder> pending = channel.drain();
        return renderer.render(pending);
    }

    /**
     * Drain pending reminders, render them, and prepend the rendered block
     * (separated by a blank line) to {@code userMessage}. If nothing is pending,
     * returns {@code userMessage} unchanged.
     */
    public String prepend(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        String block = drainAndRender();
        if (block.isEmpty()) return userMessage;
        return block + "\n\n" + userMessage;
    }
}
