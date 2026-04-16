package ai.intelliswarm.swarmai.selfimproving.ledger;

import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Telemetry dispatcher that POSTs rollups from {@link LedgerStore} to the
 * central endpoint.
 *
 * <p>Trigger paths depend on {@link SelfImprovementConfig#getTelemetryMode()}:
 * <ul>
 *   <li>{@code PER_WORKFLOW} — {@link #reportNow()} is invoked by
 *       {@code SelfImprovementEventListener} after every successful workflow.
 *       The {@code @Scheduled} cron is a no-op in this mode.</li>
 *   <li>{@code CONTINUOUS} — the {@code @Scheduled} cron fires on the configured
 *       schedule (default every 6h) and POSTs today's rollup. Good for
 *       long-running services.</li>
 * </ul>
 * <p>{@code @PreDestroy} flush fires in both modes as a safety net for graceful
 * shutdowns.
 *
 * <p>Payload schema matches {@code POST /api/v1/self-improving/telemetry}. See
 * {@code intelliswarm.ai/backend/handlers/ledger.js} for the receiving side.
 *
 * <p>Opt-in via {@code swarmai.self-improving.telemetry-enabled=true}. When
 * disabled (the default), this bean is not registered — deployments do not
 * phone home without explicit consent.
 */
public class DailyTelemetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyTelemetryScheduler.class);

    private final SelfImprovementConfig config;
    private final LedgerStore ledgerStore;
    private final String frameworkVersion;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DailyTelemetryScheduler(SelfImprovementConfig config,
                                    LedgerStore ledgerStore,
                                    String frameworkVersion) {
        this.config = config;
        this.ledgerStore = ledgerStore;
        this.frameworkVersion = frameworkVersion;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Scheduled run. Only reports in {@link SelfImprovementConfig.TelemetryMode#CONTINUOUS}
     * mode — in {@code PER_WORKFLOW} mode the listener drives reports instead,
     * so this tick is a no-op.
     *
     * <p>The server overwrites the {@code {installationId#reportDate}} row on each
     * POST, so a day's worth of 6-hour pushes end up as one final snapshot of that
     * day's activity.
     */
    @Scheduled(cron = "${swarmai.self-improving.telemetry-report-cron:0 0 */6 * * *}",
               zone = "${swarmai.self-improving.telemetry-report-zone:UTC}")
    public void reportOnCron() {
        if (config.getTelemetryMode() != SelfImprovementConfig.TelemetryMode.CONTINUOUS) {
            return;
        }
        reportFor(LocalDate.now());
    }

    /**
     * Immediate push of today's rollup. Called by the event listener in
     * {@link SelfImprovementConfig.TelemetryMode#PER_WORKFLOW} mode after every
     * successful workflow.
     */
    public void reportNow() {
        reportFor(LocalDate.now());
    }

    /**
     * Shutdown flush: also report today's partial rollup so short-lived JVMs
     * (examples, CI jobs) contribute their data. No-op if nothing changed
     * since the last report.
     */
    @PreDestroy
    public void flushOnShutdown() {
        if (!config.isTelemetryFlushOnShutdown()) return;
        reportFor(LocalDate.now());
    }

    private void reportFor(LocalDate date) {
        Optional<LedgerStore.DailyRollup> rollupOpt = ledgerStore.getDailyRollup(date);
        if (rollupOpt.isEmpty()) {
            log.debug("No ledger activity for {}, skipping telemetry report", date);
            return;
        }
        LedgerStore.DailyRollup r = rollupOpt.get();
        if (r.reported()) {
            log.debug("Rollup for {} already reported, skipping", date);
            return;
        }
        if (r.workflowRuns() == 0) {
            log.debug("Rollup for {} has zero runs, skipping", date);
            return;
        }

        try {
            Map<String, Object> payload = buildPayload(r);
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getTelemetryEndpoint() + "/api/v1/self-improving/telemetry"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ledgerStore.markDailyReported(date);
                log.info("Daily telemetry reported for {}: {} runs, {} tokens invested, {} proposals",
                        date, r.workflowRuns(), r.tokensInvested(), r.proposalsGenerated());
            } else {
                log.debug("Telemetry endpoint returned {} for {}: {}",
                        response.statusCode(), date, response.body());
            }
        } catch (Exception e) {
            // Telemetry failures are never fatal. Next run retries; markDailyReported
            // is only called on success so nothing is lost.
            log.debug("Telemetry send failed for {} (will retry next run): {}", date, e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(LedgerStore.DailyRollup r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installationId", ledgerStore.getOrCreateInstallationId());
        payload.put("reportDate", r.reportDate().toString());
        payload.put("frameworkVersion", frameworkVersion);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("javaVersion", System.getProperty("java.specification.version", "unknown"));
        runtime.put("os", classifyOs());
        payload.put("runtime", runtime);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("workflowRuns", r.workflowRuns());
        inputs.put("tokensInvested", r.tokensInvested());
        inputs.put("observationsCollected", r.observationsCollected());
        payload.put("inputs", inputs);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("proposalsGenerated", r.proposalsGenerated());
        outputs.put("tier1AutoEligible", r.tier1AutoEligible());
        outputs.put("tier2PRsFiled", r.tier2PRsFiled());
        outputs.put("tier3Proposals", r.tier3Proposals());
        outputs.put("antiPatternsDiscovered", r.antiPatternsDiscovered());
        outputs.put("skillsPromoted", r.skillsPromoted());
        payload.put("outputs", outputs);

        payload.put("categories", r.categories() != null ? r.categories() : Map.of());
        return payload;
    }

    private static String classifyOs() {
        String raw = System.getProperty("os.name", "unknown").toLowerCase();
        if (raw.contains("linux")) return "linux";
        if (raw.contains("mac") || raw.contains("darwin")) return "mac";
        if (raw.contains("win")) return "windows";
        return "other";
    }
}
