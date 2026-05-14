package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * One cache breakpoint anchored on a logical prompt slot. At most four breakpoints
 * are allowed per request (Anthropic limit). Slots are addressed by a stable name
 * so the same {@link CachePolicy} can be reused across turns without renumbering.
 *
 * <p>Recognised slot kinds:
 * <ul>
 *   <li>{@code tools}        — cache the tool registry (largest static prefix)</li>
 *   <li>{@code system}       — cache the system prompt</li>
 *   <li>{@code documents}    — cache attached PDF/text documents</li>
 *   <li>{@code messages:N}   — cache through message index {@code N} (0-based)</li>
 * </ul>
 *
 * @param slot logical anchor (e.g. {@code "system"}, {@code "messages:3"})
 * @param tier TTL tier for this breakpoint
 */
@PublicApi(since = "1.0.24")
public record CacheBreakpoint(String slot, CacheTier tier) {

    public CacheBreakpoint {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        if (slot.isBlank()) {
            throw new IllegalArgumentException("slot must not be blank");
        }
    }

    public static CacheBreakpoint fiveMinutes(String slot) {
        return new CacheBreakpoint(slot, CacheTier.FIVE_MINUTES);
    }

    public static CacheBreakpoint oneHour(String slot) {
        return new CacheBreakpoint(slot, CacheTier.ONE_HOUR);
    }
}
