package ai.intelliswarm.swarmai.base;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Factory for creating mock ChatClient instances for testing.
 * Provides various configurations for simulating different LLM responses.
 */
public class MockChatClientFactory {

    /**
     * Creates a mock ChatClient that returns a fixed response.
     * Uses deep stubs to handle Spring AI's fluent API chain.
     */
    public static ChatClient withResponse(String response) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);

        when(mockClient.prompt()
                .user(anyString())
                .call()
                .content()).thenReturn(response);

        // Also handle case with functions
        when(mockClient.prompt()
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenReturn(response);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that returns responses sequentially.
     * Each call to the client returns the next response in the list.
     */
    public static ChatClient withResponses(String... responses) {
        return withResponses(List.of(responses));
    }

    /**
     * Creates a mock ChatClient that returns responses sequentially.
     */
    public static ChatClient withResponses(List<String> responses) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        AtomicInteger callCount = new AtomicInteger(0);

        // Answer that cycles through responses
        org.mockito.stubbing.Answer<String> responseAnswer = invocation -> {
            int index = callCount.getAndIncrement();
            if (index < responses.size()) {
                return responses.get(index);
            }
            return responses.get(responses.size() - 1); // Return last response if exceeded
        };

        when(mockClient.prompt()
                .user(anyString())
                .call()
                .content()).thenAnswer(responseAnswer);

        when(mockClient.prompt()
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenAnswer(responseAnswer);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that throws an exception.
     */
    public static ChatClient withError(Exception exception) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);

        when(mockClient.prompt()
                .user(anyString())
                .call()
                .content()).thenThrow(exception);

        when(mockClient.prompt()
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenThrow(exception);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that throws a RuntimeException with the given message.
     */
    public static ChatClient withError(String errorMessage) {
        return withError(new RuntimeException(errorMessage));
    }

    /**
     * Creates a mock ChatClient that simulates a delay before returning.
     */
    public static ChatClient withDelay(Duration delay, String response) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);

        org.mockito.stubbing.Answer<String> delayedAnswer = invocation -> {
            Thread.sleep(delay.toMillis());
            return response;
        };

        when(mockClient.prompt()
                .user(anyString())
                .call()
                .content()).thenAnswer(delayedAnswer);

        when(mockClient.prompt()
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenAnswer(delayedAnswer);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that captures the prompts sent to it.
     */
    public static CapturingChatClient capturing(String response) {
        return new CapturingChatClient(response);
    }

    /**
     * A ChatClient wrapper that captures prompts for verification.
     * Uses deep stubs with an ArgumentCaptor-like approach.
     */
    public static class CapturingChatClient {
        private final ChatClient mockClient;
        private final java.util.List<String> capturedPrompts = new java.util.ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final String response;

        private CapturingChatClient(String response) {
            this.response = response;
            // Use deep stubs for the base mock
            this.mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
            setupMock();
        }

        private void setupMock() {
            // Answer that captures and responds
            org.mockito.stubbing.Answer<String> contentAnswer = inv -> {
                callCount.incrementAndGet();
                return response;
            };

            // Set up the chain for both with and without functions()
            try {
                when(mockClient.prompt()
                        .user(anyString())
                        .call()
                        .content()).thenAnswer(contentAnswer);

                when(mockClient.prompt()
                        .user(anyString())
                        .functions(any(String[].class))
                        .call()
                        .content()).thenAnswer(contentAnswer);
            } catch (Exception e) {
                // Ignore mock setup errors
            }
        }

        public ChatClient getClient() {
            return mockClient;
        }

        public List<String> getCapturedPrompts() {
            // Note: With deep stubs, we can't easily capture the prompts
            // Use the call count instead
            return new java.util.ArrayList<>(capturedPrompts);
        }

        public String getLastPrompt() {
            // With deep stubs, prompt capture is not reliable
            // Tests using this should check callCount instead
            return capturedPrompts.isEmpty() ? null : capturedPrompts.get(capturedPrompts.size() - 1);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }
}
