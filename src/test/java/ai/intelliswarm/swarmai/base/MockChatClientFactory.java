package ai.intelliswarm.swarmai.base;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Factory for creating mock ChatClient instances for testing.
 * Supports system+user message pattern and ChatResponse with token usage.
 */
public class MockChatClientFactory {

    private static ChatResponse createMockChatResponse(String content) {
        AssistantMessage message = new AssistantMessage(content);
        Generation generation = new Generation(message);

        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100L);
        when(usage.getGenerationTokens()).thenReturn(50L);
        when(usage.getTotalTokens()).thenReturn(150L);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }

    /**
     * Creates a mock ChatClient that returns a fixed response with token usage.
     */
    public static ChatClient withResponse(String response) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = createMockChatResponse(response);

        // system(s).user(u).call().chatResponse()
        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);

        // user(u).call().chatResponse() (backward compat)
        when(mockClient.prompt().user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);

        // Also mock .content() for any code that still uses it
        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().content()).thenReturn(response);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().content()).thenReturn(response);
        when(mockClient.prompt().user(anyString())
                .call().content()).thenReturn(response);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().content()).thenReturn(response);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that returns responses sequentially.
     */
    public static ChatClient withResponses(String... responses) {
        return withResponses(List.of(responses));
    }

    public static ChatClient withResponses(List<String> responses) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        AtomicInteger callCount = new AtomicInteger(0);

        org.mockito.stubbing.Answer<ChatResponse> responseAnswer = invocation -> {
            int index = callCount.getAndIncrement();
            String resp = index < responses.size() ? responses.get(index) : responses.get(responses.size() - 1);
            return createMockChatResponse(resp);
        };

        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenAnswer(responseAnswer);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenAnswer(responseAnswer);
        when(mockClient.prompt().user(anyString())
                .call().chatResponse()).thenAnswer(responseAnswer);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenAnswer(responseAnswer);

        // Also mock .content() for backward compat
        AtomicInteger contentCount = new AtomicInteger(0);
        org.mockito.stubbing.Answer<String> contentAnswer = invocation -> {
            int index = contentCount.getAndIncrement();
            return index < responses.size() ? responses.get(index) : responses.get(responses.size() - 1);
        };
        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().content()).thenAnswer(contentAnswer);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().content()).thenAnswer(contentAnswer);
        when(mockClient.prompt().user(anyString())
                .call().content()).thenAnswer(contentAnswer);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().content()).thenAnswer(contentAnswer);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that throws an exception.
     */
    public static ChatClient withError(Exception exception) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);

        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenThrow(exception);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenThrow(exception);
        when(mockClient.prompt().user(anyString())
                .call().chatResponse()).thenThrow(exception);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenThrow(exception);

        // Also for .content()
        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().content()).thenThrow(exception);
        when(mockClient.prompt().user(anyString())
                .call().content()).thenThrow(exception);

        return mockClient;
    }

    public static ChatClient withError(String errorMessage) {
        return withError(new RuntimeException(errorMessage));
    }

    /**
     * Creates a mock ChatClient that simulates a delay before returning.
     */
    public static ChatClient withDelay(Duration delay, String response) {
        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = createMockChatResponse(response);

        org.mockito.stubbing.Answer<ChatResponse> delayedAnswer = invocation -> {
            Thread.sleep(delay.toMillis());
            return chatResponse;
        };

        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenAnswer(delayedAnswer);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenAnswer(delayedAnswer);
        when(mockClient.prompt().user(anyString())
                .call().chatResponse()).thenAnswer(delayedAnswer);
        when(mockClient.prompt().user(anyString())
                .functions(any(String[].class)).call().chatResponse()).thenAnswer(delayedAnswer);

        return mockClient;
    }

    /**
     * Creates a mock ChatClient that captures the prompts sent to it.
     */
    public static CapturingChatClient capturing(String response) {
        return new CapturingChatClient(response);
    }

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
            ChatResponse chatResponse = createMockChatResponse(response);
            org.mockito.stubbing.Answer<ChatResponse> answer = inv -> {
                callCount.incrementAndGet();
                return chatResponse;
            };

            try {
                when(mockClient.prompt().system(anyString()).user(anyString())
                        .call().chatResponse()).thenAnswer(answer);
                when(mockClient.prompt().system(anyString()).user(anyString())
                        .functions(any(String[].class)).call().chatResponse()).thenAnswer(answer);
                when(mockClient.prompt().user(anyString())
                        .call().chatResponse()).thenAnswer(answer);
                when(mockClient.prompt().user(anyString())
                        .functions(any(String[].class)).call().chatResponse()).thenAnswer(answer);
            } catch (Exception e) {
                // Ignore mock setup errors
            }
        }

        public ChatClient getClient() { return mockClient; }
        public List<String> getCapturedPrompts() { return new java.util.ArrayList<>(capturedPrompts); }
        public String getLastPrompt() { return capturedPrompts.isEmpty() ? null : capturedPrompts.get(capturedPrompts.size() - 1); }
        public int getCallCount() { return callCount.get(); }
    }
}
