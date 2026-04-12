/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 */
package ai.intelliswarm.swarmai.agent.resilience;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Tracks LLM call latencies to derive dynamic per-call timeouts.
 *
 * <p>Rather than using a hardcoded timeout that is either too short for slow
 * models (causing false failures) or too long for fast models (wasting time on
 * genuine hangs), this tracker records the latency of recent successful calls
 * and derives a suggested timeout from the P95 of that window.
 *
 * <p>Formula: {@code timeoutMs = clamp(p95 * safetyMultiplier, floor, ceiling)}
 *
 * <p>Provider-keyed: different models/providers get their own window, so a fast
 * OpenAI call history doesn't penalize a slow Ollama model.
 *
 * <p>Thread-safe via synchronized blocks — sample volume is low (one write per
 * LLM call) so lock contention is negligible compared to the call itself.
 */
public class LlmLatencyTracker {

    /** Default rolling window size — recent enough to adapt, large enough to be stable. */
    public static final int DEFAULT_WINDOW_SIZE = 20;
    /** P95 multiplier — give genuine slow calls 1.5x headroom before timeout. */
    public static final double DEFAULT_SAFETY_MULTIPLIER = 1.5;
    /** Absolute minimum timeout — prevents flaky timeouts during warm-up. */
    public static final long DEFAULT_FLOOR_MS = 30_000;           // 30 seconds
    /** Absolute maximum timeout — prevents runaway timeouts on genuinely broken providers. */
    public static final long DEFAULT_CEILING_MS = 600_000;        // 10 minutes
    /** Timeout used when no samples exist (first call). */
    public static final long DEFAULT_COLD_START_MS = 120_000;     // 2 minutes

    private final int windowSize;
    private final double safetyMultiplier;
    private final long floorMs;
    private final long ceilingMs;
    private final long coldStartMs;

    /** Key → rolling window of recent latencies (ms). */
    private final java.util.Map<String, Deque<Long>> windows = new java.util.concurrent.ConcurrentHashMap<>();

    public LlmLatencyTracker() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_SAFETY_MULTIPLIER,
             DEFAULT_FLOOR_MS, DEFAULT_CEILING_MS, DEFAULT_COLD_START_MS);
    }

    public LlmLatencyTracker(int windowSize, double safetyMultiplier,
                              long floorMs, long ceilingMs, long coldStartMs) {
        if (windowSize < 3) throw new IllegalArgumentException("windowSize must be >= 3");
        if (safetyMultiplier < 1.0) throw new IllegalArgumentException("safetyMultiplier must be >= 1.0");
        if (floorMs <= 0) throw new IllegalArgumentException("floorMs must be positive");
        if (ceilingMs < floorMs) throw new IllegalArgumentException("ceilingMs must be >= floorMs");
        this.windowSize = windowSize;
        this.safetyMultiplier = safetyMultiplier;
        this.floorMs = floorMs;
        this.ceilingMs = ceilingMs;
        this.coldStartMs = Math.max(floorMs, Math.min(ceilingMs, coldStartMs));
    }

    /**
     * Record a successful LLM call latency.
     *
     * @param key arbitrary key (e.g., "openai/gpt-4o", "ollama/mistral:latest")
     * @param latencyMs observed call duration
     */
    public void recordSuccess(String key, long latencyMs) {
        if (key == null) key = "default";
        if (latencyMs <= 0) return;
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(latencyMs);
            while (window.size() > windowSize) {
                window.removeFirst();
            }
        }
    }

    /**
     * Return the suggested timeout for the given key based on recent latency.
     *
     * @param key same key used in {@link #recordSuccess(String, long)}
     * @return timeout in milliseconds, or {@link #coldStartMs} if no samples yet
     */
    public long suggestedTimeoutMs(String key) {
        if (key == null) key = "default";
        Deque<Long> window = windows.get(key);
        if (window == null) return coldStartMs;

        List<Long> snapshot;
        synchronized (window) {
            if (window.isEmpty()) return coldStartMs;
            snapshot = new ArrayList<>(window);
        }

        Collections.sort(snapshot);
        // P95 — use floor index so small windows behave sensibly
        int p95Index = (int) Math.floor(snapshot.size() * 0.95);
        if (p95Index >= snapshot.size()) p95Index = snapshot.size() - 1;
        long p95 = snapshot.get(p95Index);

        long suggested = (long) (p95 * safetyMultiplier);
        if (suggested < floorMs) return floorMs;
        if (suggested > ceilingMs) return ceilingMs;
        return suggested;
    }

    /** Current sample count for a key — useful for tests and observability. */
    public int sampleCount(String key) {
        if (key == null) key = "default";
        Deque<Long> window = windows.get(key);
        if (window == null) return 0;
        synchronized (window) {
            return window.size();
        }
    }

    /** Reset all tracked windows — useful for tests. */
    public void reset() {
        windows.clear();
    }
}
