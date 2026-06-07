package ai.intelliswarm.swarmai.agent.hook;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed family of agent-lifecycle events. Every event carries:
 * <ul>
 *   <li>{@link #correlationId()} — propagates through subagents and tool calls</li>
 *   <li>{@link #agentId()} — the agent emitting the event ({@code null} for swarm-level events)</li>
 *   <li>{@link #timestamp()} — wall-clock event time</li>
 *   <li>{@link #attributes()} — structured key/value annotations (carried into audit logs)</li>
 * </ul>
 *
 * <p>Hook implementations match on the variant they care about via
 * pattern-matching {@code switch}, or override the per-event default method on
 * {@link LifecycleHook}.
 */
@PublicApi(since = "1.0.24")
public sealed interface LifecycleEvent
        permits LifecycleEvent.UserPromptSubmit,
                LifecycleEvent.SessionStart,
                LifecycleEvent.SessionEnd,
                LifecycleEvent.PreCompact,
                LifecycleEvent.PostCompact,
                LifecycleEvent.Stop,
                LifecycleEvent.SubagentStart,
                LifecycleEvent.SubagentStop,
                LifecycleEvent.TaskCreated,
                LifecycleEvent.TaskCompleted,
                LifecycleEvent.FileChanged,
                LifecycleEvent.PermissionRequest,
                LifecycleEvent.PermissionDenied,
                LifecycleEvent.Notification {

    String correlationId();
    String agentId();
    Instant timestamp();
    Map<String, Object> attributes();

    /** User-typed prompt about to be sent to the LLM. Hook may rewrite via {@link HookResult#modify(Object)}. */
    @PublicApi(since = "1.0.24")
    record UserPromptSubmit(String correlationId, String agentId, Instant timestamp,
                            String prompt, Map<String, Object> attributes) implements LifecycleEvent {
        public UserPromptSubmit {
            Objects.requireNonNull(prompt, "prompt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record SessionStart(String correlationId, String agentId, Instant timestamp,
                        String sessionId, Map<String, Object> attributes) implements LifecycleEvent {
        public SessionStart {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record SessionEnd(String correlationId, String agentId, Instant timestamp,
                      String sessionId, String reason, Map<String, Object> attributes) implements LifecycleEvent {
        public SessionEnd {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** About to compact the conversation. Hook may BLOCK to skip compaction this turn. */
    @PublicApi(since = "1.0.24")
    record PreCompact(String correlationId, String agentId, Instant timestamp,
                      int currentTokens, int targetTokens, Map<String, Object> attributes) implements LifecycleEvent {
        public PreCompact {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record PostCompact(String correlationId, String agentId, Instant timestamp,
                       int beforeTokens, int afterTokens, Map<String, Object> attributes) implements LifecycleEvent {
        public PostCompact {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** Agent loop wants to stop. Hook may BLOCK to force another turn. */
    @PublicApi(since = "1.0.24")
    record Stop(String correlationId, String agentId, Instant timestamp,
                String stopReason, Map<String, Object> attributes) implements LifecycleEvent {
        public Stop {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record SubagentStart(String correlationId, String agentId, Instant timestamp,
                         String subagentId, String subagentType, String prompt,
                         Map<String, Object> attributes) implements LifecycleEvent {
        public SubagentStart {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record SubagentStop(String correlationId, String agentId, Instant timestamp,
                        String subagentId, String result, boolean success,
                        Map<String, Object> attributes) implements LifecycleEvent {
        public SubagentStop {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record TaskCreated(String correlationId, String agentId, Instant timestamp,
                       String taskId, String description,
                       Map<String, Object> attributes) implements LifecycleEvent {
        public TaskCreated {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record TaskCompleted(String correlationId, String agentId, Instant timestamp,
                         String taskId, boolean success, String output,
                         Map<String, Object> attributes) implements LifecycleEvent {
        public TaskCompleted {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record FileChanged(String correlationId, String agentId, Instant timestamp,
                       String path, ChangeKind kind, Map<String, Object> attributes) implements LifecycleEvent {
        public FileChanged {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public enum ChangeKind { CREATED, MODIFIED, DELETED, RENAMED }
    }

    /** Permission decision in progress — hook may pre-empt with ASK or BLOCK. */
    @PublicApi(since = "1.0.24")
    record PermissionRequest(String correlationId, String agentId, Instant timestamp,
                             String toolName, Map<String, Object> toolArguments,
                             Map<String, Object> attributes) implements LifecycleEvent {
        public PermissionRequest {
            Objects.requireNonNull(toolName, "toolName must not be null");
            toolArguments = toolArguments == null ? Map.of() : Map.copyOf(toolArguments);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record PermissionDenied(String correlationId, String agentId, Instant timestamp,
                            String toolName, String reason,
                            Map<String, Object> attributes) implements LifecycleEvent {
        public PermissionDenied {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @PublicApi(since = "1.0.24")
    record Notification(String correlationId, String agentId, Instant timestamp,
                        String channel, String body,
                        Map<String, Object> attributes) implements LifecycleEvent {
        public Notification {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
