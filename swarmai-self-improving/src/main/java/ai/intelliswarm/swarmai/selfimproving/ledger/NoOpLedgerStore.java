package ai.intelliswarm.swarmai.selfimproving.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fallback {@link LedgerStore} used when no {@code DataSource} bean is on the
 * classpath (embedded examples, unit tests, minimal quickstarts).
 *
 * <p>Keeps the framework working end-to-end without a database, but counters
 * do not survive restart and the daily telemetry scheduler will have nothing
 * to send. Users who want the "community ledger" experience must add a
 * {@code DataSource} so {@link JdbcLedgerStore} is activated.
 */
public class NoOpLedgerStore implements LedgerStore {

    private final AtomicReference<String> installationId = new AtomicReference<>();

    @Override
    public void recordRun(LedgerDelta delta, Map<String, Long> categories) {
        // Intentional no-op
    }

    @Override
    public Optional<DailyRollup> getDailyRollup(LocalDate date) {
        return Optional.empty();
    }

    @Override
    public void markDailyReported(LocalDate date) {
        // Intentional no-op
    }

    @Override
    public String getOrCreateInstallationId() {
        return installationId.updateAndGet(existing ->
                existing != null ? existing : UUID.randomUUID().toString()
        );
    }

    @Override
    public GlobalSnapshot getGlobalSnapshot() {
        return GlobalSnapshot.zero();
    }

    @Override
    public void recordObservation(String swarmId, String observationType, String description, String evidenceJson) {
        // Intentional no-op
    }

    @Override
    public List<StoredObservation> getRecentObservations(int limit) {
        return List.of();
    }

    @Override
    public void recordEvolution(ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution evolution) {
        // Intentional no-op
    }

    @Override
    public List<StoredEvolution> getRecentEvolutions(int limit) {
        return List.of();
    }
}
