package ai.intelliswarm.swarmai.agent.resilience;

import ai.intelliswarm.swarmai.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that each agent's configured LLM provider can answer a lightweight ping.
 * Intended for fail-fast checks at Swarm build time.
 */
public final class LlmHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(LlmHealthChecker.class);
    private static final String PING_PROMPT = "ping";

    private LlmHealthChecker() {
    }

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
}
