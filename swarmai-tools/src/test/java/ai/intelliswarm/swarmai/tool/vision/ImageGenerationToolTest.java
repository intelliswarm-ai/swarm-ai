package ai.intelliswarm.swarmai.tool.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ImageGenerationTool Unit Tests")
class ImageGenerationToolTest {

    private RestTemplate restTemplate;
    private ImageGenerationTool tool;
    private ObjectMapper mapper;

    @TempDir Path tmp;

    // Response as DALL-E 3 serves it: a single URL.
    private static final String DALLE3_URL_RESPONSE = """
        {
          "created": 1715000000,
          "data": [
            {
              "url": "https://example.com/img.png",
              "revised_prompt": "A high-definition 16:9 illustration of ..."
            }
          ]
        }
        """;

    // Response as a b64_json call returns.
    private static final String B64_RESPONSE_TEMPLATE = """
        { "created": 1, "data": [ { "b64_json": "%s" } ] }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        mapper = new ObjectMapper();
        tool = new ImageGenerationTool(restTemplate, mapper);
        ReflectionTestUtils.setField(tool, "apiKey", "sk-test-123");
    }

    @Test void functionName() { assertEquals("image_generate", tool.getFunctionName()); }

    @Test void writePermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.WORKSPACE_WRITE,
            tool.getPermissionLevel());
    }

    // ===== Request shape =====

    @Test
    @DisplayName("POST hits /v1/images/generations with Bearer auth")
    void postEndpoint() {
        stubPost(DALLE3_URL_RESPONSE);

        tool.execute(Map.of("prompt", "a cat"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        assertEquals("https://api.openai.com/v1/images/generations", uri.getValue().toString());
        HttpHeaders h = entity.getValue().getHeaders();
        assertEquals("Bearer sk-test-123", h.getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(h.getFirst(HttpHeaders.CONTENT_TYPE).startsWith("application/json"));
    }

    @Test
    @DisplayName("body: prompt + model + size + n + response_format are all set")
    void bodyShape() throws Exception {
        stubPost(DALLE3_URL_RESPONSE);

        tool.execute(Map.of(
            "prompt", "a red apple on snow",
            "model", "dall-e-3",
            "size", "1792x1024",
            "quality", "hd",
            "style", "natural",
            "response_format", "url"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("a red apple on snow", body.path("prompt").asText());
        assertEquals("dall-e-3", body.path("model").asText());
        assertEquals("1792x1024", body.path("size").asText());
        assertEquals(1, body.path("n").asInt());
        assertEquals("hd", body.path("quality").asText());
        assertEquals("natural", body.path("style").asText());
        assertEquals("url", body.path("response_format").asText());
    }

    @Test
    @DisplayName("gpt-image-1: response_format is NOT sent (API rejects it)")
    void gptImageOmitsResponseFormat() throws Exception {
        stubPost(String.format(B64_RESPONSE_TEMPLATE, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));

        tool.execute(Map.of(
            "prompt", "a cat",
            "model", "gpt-image-1"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertFalse(body.has("response_format"),
            "response_format must be omitted for gpt-image-1. Body:\n" + body);
    }

    // ===== Size validation =====

    @Test
    @DisplayName("dall-e-3 with unsupported size → error")
    void dalle3BadSize() {
        Object out = tool.execute(Map.of(
            "prompt", "a cat",
            "model", "dall-e-3",
            "size", "500x500"));
        assertTrue(out.toString().contains("not supported by dall-e-3"), out.toString());
    }

    @Test
    @DisplayName("dall-e-2 supports 512x512")
    void dalle2Size() {
        stubPost(DALLE3_URL_RESPONSE);

        Object out = tool.execute(Map.of(
            "prompt", "a cat",
            "model", "dall-e-2",
            "size", "512x512"));

        assertFalse(out.toString().startsWith("Error"), out.toString());
    }

    // ===== Response handling =====

    @Test
    @DisplayName("URL response: rendered as numbered list with revised_prompt")
    void urlResponseFormat() {
        stubPost(DALLE3_URL_RESPONSE);

        Object out = tool.execute(Map.of("prompt", "a cat"));

        String s = out.toString();
        assertTrue(s.contains("https://example.com/img.png"), s);
        assertTrue(s.contains("revised prompt:"), s);
        assertTrue(s.contains("Generated 1 image"));
    }

    @Test
    @DisplayName("b64 response with save_to: writes the decoded bytes to disk")
    void b64WritesToDisk() throws Exception {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5};
        String encoded = Base64.getEncoder().encodeToString(pngBytes);
        stubPost(String.format(B64_RESPONSE_TEMPLATE, encoded));

        Path outPath = tmp.resolve("cat.png");
        Object result = tool.execute(Map.of(
            "prompt", "a cat",
            "response_format", "b64_json",
            "save_to", outPath.toString()));

        String s = result.toString();
        assertTrue(s.contains("saved to:"), s);
        assertTrue(Files.exists(outPath), "File should exist at " + outPath);
        assertArrayEquals(pngBytes, Files.readAllBytes(outPath),
            "Saved bytes should match the decoded base64 payload");
    }

    @Test
    @DisplayName("b64 response with n>1 and save_to: appends -1, -2 suffixes")
    void b64MultiSaveNaming() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        String body = """
            { "data": [ { "b64_json": "%s" }, { "b64_json": "%s" } ] }
            """.formatted(encoded, encoded);
        stubPost(body);

        Path target = tmp.resolve("img.png");
        tool.execute(Map.of(
            "prompt", "two cats",
            "model", "dall-e-2",
            "size", "256x256",
            "response_format", "b64_json",
            "n", 2,
            "save_to", target.toString()));

        assertTrue(Files.exists(tmp.resolve("img-1.png")), "Expected img-1.png");
        assertTrue(Files.exists(tmp.resolve("img-2.png")), "Expected img-2.png");
    }

    // ===== Auth / error =====

    @Test
    @DisplayName("missing API key → setup hint, no network call")
    void missingKey() {
        ReflectionTestUtils.setField(tool, "apiKey", "");
        Object out = tool.execute(Map.of("prompt", "a cat"));
        assertTrue(out.toString().contains("not configured"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("401 → friendly 'key rejected' message")
    void unauthorized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        Object out = tool.execute(Map.of("prompt", "cat"));
        assertTrue(out.toString().contains("401"));
    }

    @Test
    @DisplayName("429 → rate-limit hint, no stack trace")
    void tooMany() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, null, null));

        Object out = tool.execute(Map.of("prompt", "cat"));
        assertTrue(out.toString().contains("rate-limited"));
    }

    @Test
    @DisplayName("empty prompt → error")
    void emptyPrompt() {
        Object out = tool.execute(Map.of("prompt", "   "));
        assertTrue(out.toString().startsWith("Error"));
    }

    @Test
    @DisplayName("invalid response_format → error")
    void badResponseFormat() {
        Object out = tool.execute(Map.of("prompt", "cat", "response_format", "png"));
        assertTrue(out.toString().contains("response_format"), out.toString());
    }

    // ===== base_url override =====

    @Test
    @DisplayName("base_url override is respected (e.g. Azure OpenAI, proxy)")
    void baseUrlOverride() {
        ReflectionTestUtils.setField(tool, "baseUrl", "https://azure-proxy.example/openai/");
        stubPost(DALLE3_URL_RESPONSE);

        tool.execute(Map.of("prompt", "cat"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertEquals("https://azure-proxy.example/openai/images/generations", uri.getValue().toString());
    }

    @SuppressWarnings("unchecked")
    private void stubPost(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
