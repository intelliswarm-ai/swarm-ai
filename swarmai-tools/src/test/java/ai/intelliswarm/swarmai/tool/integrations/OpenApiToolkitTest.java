package ai.intelliswarm.swarmai.tool.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenApiToolkit Unit Tests")
class OpenApiToolkitTest {

    private RestTemplate restTemplate;
    private OpenApiToolkit tool;
    private String petstoreSpec;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        tool = new OpenApiToolkit(restTemplate, new ObjectMapper());
        petstoreSpec = new String(
            Files.readAllBytes(Paths.get("src/test/resources/petstore-minimal.yaml")),
            StandardCharsets.UTF_8);
    }

    @Test void functionName() { assertEquals("openapi_call", tool.getFunctionName()); }

    @Test void dangerousPermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.DANGEROUS,
            tool.getPermissionLevel());
    }

    // ===== list_operations =====

    @Test
    @DisplayName("list_operations: enumerates every operationId with method + path + summary")
    void listOperations() {
        Object out = tool.execute(Map.of("operation", "list_operations", "spec", petstoreSpec));

        String s = out.toString();
        assertTrue(s.contains("Minimal Petstore"));
        assertTrue(s.contains("v1.0.0"));
        assertTrue(s.contains("Base URL: https://petstore.example.com/v1"));
        assertTrue(s.contains("**listPets** — `GET /pets`"), s);
        assertTrue(s.contains("**createPet** — `POST /pets`"));
        assertTrue(s.contains("**getPet** — `GET /pets/{petId}`"));
        assertTrue(s.contains("**deletePet** — `DELETE /pets/{petId}`"));
        // Required path param must be marked with *
        assertTrue(s.contains("petId (path*)"), s);
        // Operation count
        assertTrue(s.contains("(4 operation(s))"));
    }

    @Test
    @DisplayName("list_operations: missing spec → clear error")
    void listRequiresSpec() {
        Object out = tool.execute(Map.of("operation", "list_operations"));
        assertTrue(out.toString().contains("spec_url"));
    }

    // ===== invoke =====

    @Test
    @DisplayName("invoke: GET listPets with query param hits the right URL + method")
    void invokeList() {
        stubOk("[]");

        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "listPets",
            "query_params", Map.of("limit", 5)));

        assertTrue(out.toString().contains("HTTP 200"));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertEquals("https://petstore.example.com/v1/pets?limit=5", url);
    }

    @Test
    @DisplayName("invoke: GET getPet substitutes {petId} and URL-encodes values")
    void invokePathParam() {
        stubOk("{\"id\":\"Dog #7\"}");

        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "getPet",
            "path_params", Map.of("petId", "Dog #7")));

        assertTrue(out.toString().contains("HTTP 200"));
        assertTrue(out.toString().contains("Dog #7"), out.toString());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertEquals("https://petstore.example.com/v1/pets/Dog+%237", uri.getValue().toString());
    }

    @Test
    @DisplayName("invoke: missing required path param → error, no network call")
    void invokeMissingPathParam() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "getPet"));

        assertTrue(out.toString().contains("missing required path parameter 'petId'"),
            out.toString());
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("invoke: POST createPet serialises an object body to JSON")
    void invokePostBody() {
        stubOk("{\"id\":\"42\"}", HttpStatus.CREATED);

        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "createPet",
            "body", Map.of("name", "Fido")));

        assertTrue(out.toString().contains("HTTP 201"));

        ArgumentCaptor<HttpEntity> ent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), ent.capture(), eq(String.class));
        Object body = ent.getValue().getBody();
        assertTrue(body instanceof String);
        assertTrue(((String) body).contains("\"name\":\"Fido\""));
        assertTrue(ent.getValue().getHeaders().getContentType().toString().startsWith("application/json"));
    }

    @Test
    @DisplayName("invoke: DELETE deletePet uses DELETE method")
    void invokeDelete() {
        stubOk("", HttpStatus.NO_CONTENT);

        tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "deletePet",
            "path_params", Map.of("petId", "42")));

        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("invoke: bearer_token becomes Authorization: Bearer header")
    void invokeBearerToken() {
        stubOk("[]");

        tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "listPets",
            "bearer_token", "SECRET_TOKEN"));

        ArgumentCaptor<HttpEntity> ent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), ent.capture(), eq(String.class));
        HttpHeaders h = ent.getValue().getHeaders();
        assertEquals("Bearer SECRET_TOKEN", h.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("invoke: unknown operationId → helpful error")
    void invokeUnknownOp() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "adoptPet"));

        assertTrue(out.toString().contains("not found"));
    }

    @Test
    @DisplayName("invoke: supports synthesized operation IDs when operationId is omitted")
    void invokeWithSynthesizedOperationId() {
        String specWithoutIds = """
            openapi: 3.0.1
            info:
              title: No OpId API
              version: "1.0"
            servers:
              - url: https://example.com
            paths:
              /pets/{petId}:
                get:
                  summary: Read pet
                  parameters:
                    - in: path
                      name: petId
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: ok
            """;
        stubOk("{\"id\":\"42\"}");

        Object listOut = tool.execute(Map.of("operation", "list_operations", "spec", specWithoutIds));
        String synthesizedOperationId = "get__pets_petId_";
        assertTrue(listOut.toString().contains("**" + synthesizedOperationId + "**"), listOut.toString());

        Object invokeOut = tool.execute(Map.of(
            "operation", "invoke",
            "spec", specWithoutIds,
            "operation_id", synthesizedOperationId,
            "path_params", Map.of("petId", "42")));

        assertTrue(invokeOut.toString().contains("HTTP 200"), invokeOut.toString());
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("invoke: HTTP 4xx surfaces status + response body, doesn't throw")
    void invoke4xx() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", null,
                "{\"error\":\"pet not found\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "getPet",
            "path_params", Map.of("petId", "ghost")));

        assertTrue(out.toString().contains("HTTP 404"));
        assertTrue(out.toString().contains("pet not found"));
    }

    @Test
    @DisplayName("invoke: JSON response is pretty-printed")
    void invokeJsonPrettyPrint() {
        stubOk("{\"id\":\"42\",\"name\":\"Fido\"}");

        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec", petstoreSpec,
            "operation_id", "listPets"));

        String s = out.toString();
        // Pretty printer adds newlines between fields
        assertTrue(s.contains("\"id\" : \"42\""), s);
        assertTrue(s.contains("\"name\" : \"Fido\""), s);
    }

    // ===== Spec cache =====

    @Test
    @DisplayName("same inline spec is parsed once and reused")
    void specCache() {
        tool.execute(Map.of("operation", "list_operations", "spec", petstoreSpec));
        // Same spec → should hit cache. There's no public hook to check cache size, but the test
        // simply verifies a second call does not throw.
        Object out2 = tool.execute(Map.of("operation", "list_operations", "spec", petstoreSpec));
        assertTrue(out2.toString().contains("listPets"));
    }

    @Test
    @DisplayName("malformed spec yields a parse error, no NPE")
    void specMalformed() {
        Object out = tool.execute(Map.of("operation", "list_operations", "spec", "NOT_A_SPEC"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== Helpers =====

    private void stubOk(String body) { stubOk(body, HttpStatus.OK); }

    @SuppressWarnings("unchecked")
    private void stubOk(String body, HttpStatus status) {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, status));
    }
}
