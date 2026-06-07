package ai.intelliswarm.swarmai.agent.wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Unit tests for the webhook signal controller (Phase 3 of pause-resume, 1.0.21+). */
@DisplayName("SignalWebhookController")
class SignalWebhookControllerTest {

    private SignalDispatcher dispatcher;
    private SignalWebhookController controller;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dispatcher = new SignalDispatcher();
        controller = new SignalWebhookController(dispatcher, /*maxPayloadBytes*/ 1024);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postWithoutPendingWaitDropsAndReports() throws Exception {
        MvcResult result = mvc.perform(post("/api/signals/some.key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals("some.key", body.get("key").asText());
        assertFalse(body.get("delivered").asBoolean());
        assertEquals("no matching pending wait — signal dropped",
                body.get("note").asText());
        assertEquals(1, dispatcher.droppedSignals());
    }

    @Test
    void postMatchingPendingWaitDelivers() throws Exception {
        WaitForSignalRequest wait = dispatcher.registerWait("k", "r", Duration.ofSeconds(2));
        CountDownLatch received = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                Signal s = dispatcher.awaitSignal(wait.id(), Duration.ofSeconds(2));
                if ("hello".equals(s.payload())) received.countDown();
            } catch (RuntimeException ignored) {}
        });
        waiter.setDaemon(true);
        waiter.start();

        MvcResult result = mvc.perform(post("/api/signals/k")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("delivered").asBoolean());
        assertTrue(received.await(2, TimeUnit.SECONDS));
    }

    @Test
    void emptyPayloadIsAllowed() throws Exception {
        MvcResult result = mvc.perform(post("/api/signals/k"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(0, body.get("payloadBytes").asInt());
    }

    @Test
    void payloadOverLimitRejected() throws Exception {
        String big = "x".repeat(2000);  // exceeds 1024-byte cap
        mvc.perform(post("/api/signals/k")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getPendingShowsNoneInitially() throws Exception {
        mvc.perform(get("/api/signals/_pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.droppedSignals").value(0))
                .andExpect(jsonPath("$.waits").isArray());
    }

    @Test
    void getPendingShowsRegisteredWaits() throws Exception {
        dispatcher.registerWait("alpha", "first wait", Duration.ofMinutes(1));
        dispatcher.registerWait("beta", "second wait", null);

        MvcResult result = mvc.perform(get("/api/signals/_pending"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(2, body.get("pendingCount").asInt());
        assertEquals(2, body.get("waits").size());
        // First wait has expiresAt, second doesn't — the controller only emits the field when present
        boolean firstHasExpiry = body.get("waits").get(0).has("expiresAt");
        boolean secondHasExpiry = body.get("waits").get(1).has("expiresAt");
        // Order may vary by registration time; just confirm at least one has and at least one doesn't
        assertTrue(firstHasExpiry || secondHasExpiry);
        assertFalse(firstHasExpiry && secondHasExpiry);

        // Cleanup
        dispatcher.pendingWaits().forEach(w -> dispatcher.cancel(w.id()));
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mvc.perform(get("/api/signals/_health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.pendingCount").value(0));
    }

    @Test
    void blankKeyRejected() throws Exception {
        // The path variable can't actually be blank in Spring routing, but if
        // it somehow is, the controller's defensive check returns 400.
        // We exercise that defensive path by calling the method directly.
        var resp = controller.signal("  ", "p");
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().get("error").toString().contains("blank"));
    }

    @Test
    void multiplePostsCountDroppedSignals() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/signals/missing.key").content("p"))
                    .andExpect(status().isOk());
        }
        assertEquals(3, dispatcher.droppedSignals());
    }
}
