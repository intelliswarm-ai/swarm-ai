package ai.intelliswarm.swarmai.agent.resilience;

import ai.intelliswarm.swarmai.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that each agent's configured LLM provider can answer lightweight probes,
 * and optionally benchmarks the provider's latency to seed dynamic timeouts.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #assertHealthy(List)} — single ping per agent, fails fast on any error.</li>
 *   <li>{@link #benchmarkAndSeed(List, LlmLatencyTracker, int)} — sends N probes per
 *       unique model, records durations into the latency tracker, so the very first
 *       real workflow call already has a model-specific timeout instead of the
 *       cold-start default.</li>
 * </ul>
 *
 * <p>Benchmarking uses a short, representative prompt — not a trivial "ping" —
 * so the observed latency reflects real inference time, not just TCP roundtrip.
 */
public final class LlmHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(LlmHealthChecker.class);

    /** Short prompt for health checks — must return quickly. */
    private static final String PING_PROMPT = "ping";

    /** Longer, representative prompt for latency benchmarking — exercises real inference. */
    private static final String BENCHMARK_PROMPT =
            "In one sentence, name three advantages of multi-agent orchestration over a single LLM call.";

    /** Default probe count for benchmark runs. */
    public static final int DEFAULT_BENCHMARK_PROBES = 3;

    private LlmHealthChecker() {
    }

    /**
     * Fail fast if any agent's LLM provider can't answer a trivial ping.
     */
    public static void assertHealthy(List<Agent> agents) {
        List<String> failures = new ArrayList<>();

        for (Agent agent : agents) {
            try {
                String response = agent.getChatClient()
                        .prompt()
                        .user(PING_PROMPT)
                        .call()
                        .content();

                if (response == null || response.isBlank()) {
                    failures.add("Agent '" + agent.getRole() + "' returned an empty ping response");
                }
            } catch (Exception e) {
                failures.add("Agent '" + agent.getRole() + "' ping failed: " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            failures.forEach(failure -> logger.warn("LLM health check failed: {}", failure));
            throw new IllegalStateException(
                    "LLM health check failed for " + failures.size() + " agent(s): " + String.join("; ", failures));
        }
    }

    /**
     * Benchmark each unique model used by the agents and seed the latency tracker
     * with the observed durations. This gives the first real workflow call a realistic,
     * model-specific timeout instead of the conservative cold-start default.
     *
     * <p>Runs {@code probeCount} sequential probes per unique model (not per agent),
     * so duplicate agents on the same model don't waste API calls.
     *
     * @param agents the agents whose models to benchmark
     * @param tracker the latency tracker to seed
     * @param probeCount how many probes to send per unique model (>= 1)
     * @return a {@link BenchmarkResult} with per-model statistics
     */
    public static BenchmarkResult benchmarkAndSeed(
            List<Agent> agents,
            LlmLatencyTracker tracker,
            int probeCount) {
        if (probeCount < 1) probeCount = DEFAULT_BENCHMARK_PROBES;

        // Group agents by their latency key (model name or "default")
        java.util.Map<String, Agent> byKey = new java.util.LinkedHashMap<>();
        for (Agent agent : agents) {
            String key = agent.getModelName() != null && !agent.getModelName().isBlank()
                    ? agent.getModelName() : "default";
            byKey.putIfAbsent(key, agent);
        }

        List<ModelBenchmark> benchmarks = new ArrayList<>();

        for (java.util.Map.Entry<String, Agent> entry : byKey.entrySet()) {
            String key = entry.getKey();
            Agent agent = entry.getValue();
            List<Long> latencies = new ArrayList<>();

            for (int i = 0; i < probeCount; i++) {
                long start = System.currentTimeMillis();
                try {
                    String response = agent.getChatClient()
                            .prompt()
                            .user(BENCHMARK_PROMPT)
                            .call()
                            .content();
                    long elapsed = System.currentTimeMillis() - start;
                    if (response != null && !response.isBlank()) {
                        latencies.add(elapsed);
                        tracker.recordSuccess(key, elapsed);
                    } else {
                        logger.warn("Benchmark probe {}/{} for model '{}' returned empty response",
                                i + 1, probeCount, key);
                    }
                } catch (Exception e) {
                    logger.warn("Benchmark probe {}/{} for model '{}' failed: {}",
                            i + 1, probeCount, key, e.getMessage());
                }
            }

            if (latencies.isEmpty()) {
                logger.warn("Benchmark for model '{}' collected no samples — using cold-start timeout", key);
                benchmarks.add(new ModelBenchmark(key, 0, 0, 0, 0));
            } else {
                long min = latencies.stream().mapToLong(Long::longValue).min().orElse(0);
                long max = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
                long mean = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
                long suggested = tracker.suggestedTimeoutMs(key);
                benchmarks.add(new ModelBenchmark(key, latencies.size(), min, max, mean));
                logger.info("LLM benchmark [{}]: {} samples, min={}ms, mean={}ms, max={}ms → timeout={}ms",
                        key, latencies.size(), min, mean, max, suggested);
            }
        }

        return new BenchmarkResult(benchmarks);
    }

    /** Per-model benchmark statistics. */
    public record ModelBenchmark(
            String modelKey,
            int samples,
            long minLatencyMs,
            long maxLatencyMs,
            long meanLatencyMs
    ) {}

    /** Aggregate result of a benchmark run across multiple models. */
    public record BenchmarkResult(List<ModelBenchmark> perModel) {
        public int totalProbes() {
            return perModel.stream().mapToInt(ModelBenchmark::samples).sum();
        }
    }
}
