package ai.intelliswarm.swarmai.agent.streaming;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventSseTest {

    @Test
    void heartbeatBridgeSubscribesUpstreamOnce() {
        AtomicInteger subscriptions = new AtomicInteger();

        Flux<AgentEvent> upstream = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just(
                    new AgentEvent.AgentDelta("a", "x", System.currentTimeMillis()),
                    new AgentEvent.AgentFinished("a", null, System.currentTimeMillis())
            );
        });

        StepVerifier.create(AgentEventSse.toServerSentEventsWithHeartbeat(upstream, Duration.ofMillis(5)))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(subscriptions.get()).isEqualTo(1);
    }
}
