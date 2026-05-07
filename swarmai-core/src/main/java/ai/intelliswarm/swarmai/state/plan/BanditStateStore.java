package ai.intelliswarm.swarmai.state.plan;

import java.io.IOException;

/**
 * Persistence contract for {@link BanditState}. Implementations decide where
 * the snapshot lives — local filesystem, JDBC, blob store, etc. — and how
 * concurrent writes are serialised. The SPI is deliberately small: load and
 * save, that's it. Lifecycle (when to load, when to save, how often) is the
 * caller's responsibility.
 *
 * <p>Typical wiring on application startup:
 * <pre>
 *   BanditStateStore store = new FileBanditStateStore(Path.of("data/bandit.json"));
 *   BanditApprovalPolicy bandit = new BanditApprovalPolicy();
 *   bandit.restore(store.load());     // recover learning from prior run
 *   // ... run the workflow ...
 *   store.save(bandit.snapshot());    // persist before shutdown
 * </pre>
 *
 * <p>Implementations should be:
 * <ul>
 *   <li><b>Atomic on save</b> — a crash mid-write must not produce a corrupt
 *       file. {@link FileBanditStateStore} achieves this via temp-file +
 *       atomic rename.</li>
 *   <li><b>Idempotent on load</b> — multiple {@link #load} calls return
 *       equal state for a stable backing store. A missing/empty backing
 *       store returns {@link BanditState#empty()} — never throws.</li>
 *   <li><b>Thread-safe for concurrent save calls</b> — typical scheduling is
 *       a serial executor + a single store instance, but defensive impls
 *       should serialise internally.</li>
 * </ul>
 */
public interface BanditStateStore {

    /**
     * Load the most recent saved state. Returns {@link BanditState#empty()}
     * if no state has ever been saved (e.g. first run, missing file).
     *
     * @throws IOException if the backing store exists but is unreadable —
     *                     callers can decide whether to crash or proceed
     *                     with an empty state.
     */
    BanditState load() throws IOException;

    /**
     * Persist {@code state}. Implementations are expected to write atomically
     * — a partial write must not leave the store in a corrupted state.
     *
     * @throws IOException if the write fails. Typical handling: log and
     *                     proceed; the in-memory bandit continues learning
     *                     and a future {@link #save} call may succeed.
     */
    void save(BanditState state) throws IOException;
}
