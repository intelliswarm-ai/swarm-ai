package ai.intelliswarm.swarmai.agent.wait;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the WaitForSignal primitive (Phases 1-3).
 *
 * <ul>
 *   <li>Always provides a singleton {@link SignalDispatcher} unless the
 *       application defines one.</li>
 *   <li>Provides {@link WallClockSignalSource} when an {@code @Bean} of that
 *       type isn't already declared.</li>
 *   <li>Provides {@link SignalWebhookController} when Spring web is on the
 *       classpath AND {@code swarmai.signal.webhook.enabled} is true (default
 *       false — explicit opt-in keeps the HTTP surface off unless asked for).</li>
 * </ul>
 *
 * <h2>Configuration properties</h2>
 * <table>
 *   <tr><th>Property</th><th>Default</th><th>Purpose</th></tr>
 *   <tr><td>{@code swarmai.signal.webhook.enabled}</td><td>false</td>
 *       <td>Mount the HTTP controller</td></tr>
 *   <tr><td>{@code swarmai.signal.webhook.base-path}</td><td>/api/signals</td>
 *       <td>Prefix for the controller's endpoints</td></tr>
 *   <tr><td>{@code swarmai.signal.webhook.max-payload-bytes}</td><td>65536</td>
 *       <td>Cap on the {@code POST} body size accepted as a signal payload</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
public class SignalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SignalDispatcher signalDispatcher() {
        return new SignalDispatcher();
    }

    @Bean
    @ConditionalOnMissingBean
    public WallClockSignalSource wallClockSignalSource(SignalDispatcher dispatcher) {
        return new WallClockSignalSource(dispatcher);
    }

    /**
     * The webhook controller is opt-in (default disabled) because mounting an
     * HTTP endpoint that delivers signals into the agent's tool stack is a
     * security-sensitive choice — operators should explicitly enable it after
     * deciding on auth/rate-limit policy.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "swarmai.signal.webhook", name = "enabled", havingValue = "true")
    public static class WebhookConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SignalWebhookController signalWebhookController(
                SignalDispatcher dispatcher,
                @Value("${swarmai.signal.webhook.max-payload-bytes:65536}") int maxPayloadBytes) {
            return new SignalWebhookController(dispatcher, maxPayloadBytes);
        }
    }
}
