import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;

public class TestV1Responses {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null) {
            System.out.println("ERROR: OPENAI_API_KEY not set");
            System.exit(1);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        ObjectMapper json = new ObjectMapper();

        // Build a simple request
        Map<String, Object> body = Map.of(
            "model", "gpt-5.1-codex-mini",
            "max_completion_tokens", 100,
            "messages", List.of(
                Map.of("role", "user", "content", "Write a simple hello world function in Java")
            )
        );

        String jsonBody = json.writeValueAsString(body);
        System.out.println("REQUEST BODY:");
        System.out.println(jsonBody);
        System.out.println("\n" + "=".repeat(80) + "\n");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        System.out.println("Calling v1/responses endpoint...\n");
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("HTTP " + response.statusCode());
        System.out.println("\nRESPONSE BODY:");
        System.out.println(response.body());

        System.out.println("\n" + "=".repeat(80) + "\n");
        System.out.println("PARSED JSON STRUCTURE:");
        try {
            Map<String, Object> parsed = json.readValue(response.body(), Map.class);
            System.out.println("Top-level keys: " + parsed.keySet());
            for (String key : parsed.keySet()) {
                Object val = parsed.get(key);
                System.out.println("  " + key + ": " + (val != null ? val.getClass().getSimpleName() : "null"));
                if (val instanceof Map) {
                    System.out.println("    sub-keys: " + ((Map<?, ?>) val).keySet());
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to parse: " + e.getMessage());
        }
    }
}
