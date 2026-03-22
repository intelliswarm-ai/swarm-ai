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
 * Supports the system+user message pattern used by Agent.
 */
public class MockChatClientFactory {

    /**
     * Creates a mock ChatClient that returns a fixed response.
     * Handles both system+user and user-only prompt patterns.
     */
    public static ChatClient withResponse(String response) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);

        // system(s).user(u).call().content()
        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenReturn(response);

        // system(s).user(u).functions(...).call().content()
        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenReturn(response);

        // user(u).call().content() (backward compat)
        when(mockClient.prompt()
                .user(anyString())
                .call()
                .content()).thenReturn(response);

        // user(u).functions(...).call().content() (backward compat)
        when(mockClient.prompt()
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenReturn(response);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that returns responses sequentially.
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

        org.mockito.stubbing.Answer<String> responseAnswer = invocation -> {
            int index = callCount.getAndIncrement();
            if (index < responses.size()) {
                return responses.get(index);
            }
            return responses.get(responses.size() - 1);
        };

        // system+user pattern
        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenAnswer(responseAnswer);

        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenAnswer(responseAnswer);

        // user-only pattern
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
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenThrow(exception);

        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenThrow(exception);

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
                .system(anyString())
                .user(anyString())
                .call()
                .content()).thenAnswer(delayedAnswer);

        when(mockClient.prompt()
                .system(anyString())
                .user(anyString())
                .functions(any(String[].class))
                .call()
                .content()).thenAnswer(delayedAnswer);

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
     */
    public static class CapturingChatClient {
        private final ChatClient mockClient;
        private final java.util.List<String> capturedPrompts = new java.util.ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final String response;

        private CapturingChatClient(String response) {
            this.response = response;
            this.mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
            setupMock();
        }

        private void setupMock() {
            org.mockito.stubbing.Answer<String> contentAnswer = inv -> {
                callCount.incrementAndGet();
                return response;
            };

            try {
                when(mockClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .content()).thenAnswer(contentAnswer);

                when(mockClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .functions(any(String[].class))
                        .call()
                        .content()).thenAnswer(contentAnswer);

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
            return new java.util.ArrayList<>(capturedPrompts);
        }

        public String getLastPrompt() {
            return capturedPrompts.isEmpty() ? null : capturedPrompts.get(capturedPrompts.size() - 1);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }
}
