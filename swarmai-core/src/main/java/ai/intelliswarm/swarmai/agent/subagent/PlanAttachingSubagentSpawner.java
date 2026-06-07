package ai.intelliswarm.swarmai.agent.subagent;

import ai.intelliswarm.swarmai.state.plan.PlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SubagentSpawner} decorator that bridges subagent results into the
 * {@link PlanChannelAccessor plan channel}. When a spec carries the
 * {@link #ATTACH_KEY attachToEntryId} key in its
 * {@link SubagentSpec#parentMetadata}, the decorator wraps the spawned
 * {@link Subagent} so that — on completion — the {@link SubagentResult} is
 * attached to the referenced entry's {@link PlanEntry#metadata} under
 * {@code "subagent.<spec.type>"}.
 *
 * <p>Specs without {@code attachToEntryId} pass through unchanged — the
 * decorator is a no-op for them. This means existing call sites that don't
 * opt in keep their current behaviour.
 *
 * <p>The attached value is a small {@code Map<String, Object>} with:
 * <ul>
 *   <li>{@code id} — the {@link SubagentResult#id} (matches {@link SubagentSpec#id})</li>
 *   <li>{@code status} — the terminal status as a String</li>
 *   <li>{@code output} — the model's final output (or failure message)</li>
 *   <li>{@code metrics} — the subagent's metrics map (durationMs, tokens, etc.)</li>
 * </ul>
 *
 * <p>The attachment runs once per subagent — the first {@link Subagent#awaitResult}
 * call attaches; subsequent calls return the cached result without re-attaching.
 * If the target entry has been removed from the plan by the time the subagent
 * finishes, the attachment is logged and skipped — the {@code SubagentResult}
 * is still returned to the caller.
 *
 * <p>Wiring:
 * <pre>
 *   SubagentSpawner spawner = new PlanAttachingSubagentSpawner(
 *           new InProcessSubagentSpawner(chatClient, executor),
 *           channel);
 *
 *   SubagentSpec spec = SubagentSpec.builder()
 *           .type("auditor")
 *           .systemPrompt(...)
 *           .userPrompt(...)
 *           .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, parentEntryId)
 *           .build();
 *
 *   Subagent sub = spawner.spawn(spec);
 *   sub.awaitResult(Duration.ofMinutes(1));   // result attaches to parentEntryId
 * </pre>
 */
public final class PlanAttachingSubagentSpawner implements SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(PlanAttachingSubagentSpawner.class);

    /** Key under {@link SubagentSpec#parentMetadata} that names the entry to attach to. */
    public static final String ATTACH_KEY = "attachToEntryId";

    /** Prefix for the metadata key under which the attachment lands on the target entry. */
    public static final String METADATA_KEY_PREFIX = "subagent.";

    private final SubagentSpawner delegate;
    private final PlanChannelAccessor channel;

    public PlanAttachingSubagentSpawner(SubagentSpawner delegate, PlanChannelAccessor channel) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public Subagent spawn(SubagentSpec spec) {
        Subagent inner = delegate.spawn(spec);
        Object attachId = spec.parentMetadata().get(ATTACH_KEY);
        if (!(attachId instanceof String s) || s.isBlank()) {
            return inner; // no attachment requested; pass through
        }
        return new AttachingSubagent(inner, s, spec.type(), channel);
    }

    /**
     * Decorator handle that intercepts {@link #awaitResult} to attach the
     * subagent's terminal result to the configured plan entry's metadata.
     */
    private static final class AttachingSubagent implements Subagent {

        private final Subagent inner;
        private final String entryId;
        private final String subagentType;
        private final PlanChannelAccessor channel;
        private volatile boolean attached;

        AttachingSubagent(Subagent inner, String entryId, String subagentType,
                          PlanChannelAccessor channel) {
            this.inner = inner;
            this.entryId = entryId;
            this.subagentType = subagentType;
            this.channel = channel;
        }

        @Override
        public String id() {
            return inner.id();
        }

        @Override
        public SubagentStatus status() {
            return inner.status();
        }

        @Override
        public synchronized SubagentResult awaitResult(Duration waitUntil) {
            SubagentResult result = inner.awaitResult(waitUntil);
            if (!attached && result.isTerminal()) {
                attachToPlan(result);
                attached = true;
            }
            return result;
        }

        @Override
        public void cancel() {
            inner.cancel();
        }

        private void attachToPlan(SubagentResult result) {
            String metadataKey = METADATA_KEY_PREFIX + subagentType;
            Map<String, Object> attachment = buildAttachment(result);
            try {
                channel.update(plan -> {
                    PlanEntry current = plan.byId(entryId).orElse(null);
                    if (current == null) {
                        log.warn("subagent {} ({}): cannot attach to unknown entry id {}",
                                inner.id(), subagentType, entryId);
                        return plan;
                    }
                    PlanEntry updated = current.withMetadataPut(metadataKey, attachment);
                    return plan.with(updated);
                });
            } catch (RuntimeException e) {
                // Don't let attachment failures break the caller's awaitResult flow.
                log.warn("subagent {} ({}): failed to attach result to entry {}: {}",
                        inner.id(), subagentType, entryId, e.getMessage());
            }
        }

        private static Map<String, Object> buildAttachment(SubagentResult result) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", result.id());
            map.put("status", result.status().name());
            map.put("output", result.output());
            map.put("metrics", result.metrics());
            return map;
        }
    }
}
