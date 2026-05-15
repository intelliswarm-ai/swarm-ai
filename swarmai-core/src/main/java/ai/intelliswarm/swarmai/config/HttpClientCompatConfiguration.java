package ai.intelliswarm.swarmai.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Force Spring's auto-configured {@link RestClient.Builder} to send chat
 * requests with an explicit {@code Content-Length} header instead of
 * {@code Transfer-Encoding: chunked}.
 *
 * <p>OpenAI's edge (as observed starting 2026-05-14 under JDK 21.0.10) returns
 * truncated chat-completion responses to chunked tool-bearing POSTs — every
 * downstream Jackson parse hits {@code JsonEOFException: Unexpected end-of-input}.
 * The exact same request body sent with a known {@code Content-Length} returns
 * a clean response. This was verified by routing requests through a local
 * proxy that re-frames chunked into Content-Length: the framework's calls
 * succeed unchanged.
 *
 * <p>We use {@link SimpleClientHttpRequestFactory} with
 * {@link SimpleClientHttpRequestFactory#setBufferRequestBody(boolean) buffer=true}
 * — the HttpURLConnection-backed factory pre-buffers the body and sends an
 * accurate Content-Length. This is heavier on memory than streaming but only
 * an issue for very large prompts (~megabytes), well above any sensible
 * chat-completion request size.
 *
 * <p>Backward compatible: only activates when no {@link ClientHttpRequestFactory}
 * bean is registered by the user (allowing overrides).
 *
 * @since 1.0.24
 */
@Configuration
@ConditionalOnClass(RestClient.class)
public class HttpClientCompatConfiguration {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(HttpClientCompatConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ClientHttpRequestFactory.class)
    public ClientHttpRequestFactory swarmAiClientHttpRequestFactory() {
        logger.info("HttpClientCompatConfiguration: installing buffered "
                + "HttpURLConnection factory (Content-Length, not chunked) — "
                + "workaround for OpenAI truncating chunked tool-call responses.");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setBufferRequestBody(true);
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        return factory;
    }

    @Bean
    @Primary
    public RestClient.Builder swarmAiRestClientBuilder(
            ClientHttpRequestFactory swarmAiClientHttpRequestFactory) {
        return RestClient.builder().requestFactory(swarmAiClientHttpRequestFactory);
    }

    /**
     * Defensive catch-all: rewrite any other {@link RestClient.Builder} the
     * application context creates so Spring AI cannot bypass our factory by
     * resolving the builder via a different bean name or qualifier.
     */
    @Bean
    public static BeanPostProcessor swarmAiRestClientBuilderRewriter(
            org.springframework.beans.factory.ObjectProvider<ClientHttpRequestFactory> factory) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestClient.Builder b) {
                    ClientHttpRequestFactory f = factory.getIfAvailable();
                    if (f != null) {
                        logger.info("HttpClientCompatConfiguration: rewriting "
                                + "RestClient.Builder bean '{}' to buffered factory.", beanName);
                        return b.requestFactory(f);
                    }
                }
                return bean;
            }
        };
    }
}
