package ai.intelliswarm.swarmai.tool.discovery;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which catalogued tools are currently loaded for the agent's
 * conversation, plus which are pending loading on the next turn. Enables
 * <b>true deferred tool loading</b>: the harness starts the agent with only
 * a small bootstrap tool set ({@code tool_search} + {@code tool_load}); the
 * agent searches the catalogue, requests specific tools to be loaded via
 * {@code tool_load}, and the harness adds those callbacks to the next turn's
 * tool list.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct with a {@link ToolCatalog} and a name → callback supplier
 *       map. Suppliers are lazy: callbacks are only constructed when actually
 *       loaded.</li>
 *   <li>The agent calls {@code tool_load(name)} via {@link ToolLoadTool}.
 *       Internally that calls {@link #requestLoad(String)}, which queues the
 *       tool in the pending set.</li>
 *   <li>Between LLM turns, the harness calls {@link #applyPendingLoads()} to
 *       materialize the queued callbacks. Returns how many were newly loaded.</li>
 *   <li>The harness includes {@link #loadedCallbacks()} in the next turn's
 *       Spring AI {@code toolCallbacks(...)} list.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * Backed by {@link ConcurrentHashMap}. Concurrent {@code requestLoad} calls
 * during a multi-tool turn are safe.
 */
public final class ToolLoader {

    private final ToolCatalog catalog;
    private final Map<String, java.util.function.Supplier<ToolCallback>> suppliers
            = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, ToolCallback> loaded = Collections.synchronizedMap(new LinkedHashMap<>());

    public ToolLoader(ToolCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Register a callback supplier for a catalogued tool name. The supplier
     * is invoked the first time the tool is loaded (lazy materialization).
     */
    public void registerCallback(String name, java.util.function.Supplier<ToolCallback> supplier) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(supplier, "supplier");
        if (!catalog.contains(name)) {
            throw new IllegalArgumentException(
                    "tool '" + name + "' is not in the catalog; register the entry first");
        }
        suppliers.put(name, supplier);
    }

    /** Convenience: register an already-built callback. */
    public void registerCallback(String name, ToolCallback callback) {
        Objects.requireNonNull(callback, "callback");
        registerCallback(name, () -> callback);
    }

    /** Request that {@code name} be loaded on the next turn. */
    public LoadRequestStatus requestLoad(String name) {
        Objects.requireNonNull(name, "name");
        if (loaded.containsKey(name)) {
            return LoadRequestStatus.ALREADY_LOADED;
        }
        if (!catalog.contains(name)) {
            return LoadRequestStatus.NOT_IN_CATALOG;
        }
        if (!suppliers.containsKey(name)) {
            return LoadRequestStatus.NO_CALLBACK_REGISTERED;
        }
        pending.add(name);
        return LoadRequestStatus.PENDING;
    }

    /**
     * Materialize every pending name into a loaded callback. Returns the
     * number of tools newly loaded — typically called between LLM turns.
     * A zero return signals the orchestration loop can exit (the agent
     * didn't request anything new this turn).
     */
    public int applyPendingLoads() {
        int loadedCount = 0;
        // Drain pending atomically — copy then clear, so concurrent
        // requestLoad calls during this method don't get dropped silently.
        List<String> toLoad;
        synchronized (pending) {
            toLoad = new ArrayList<>(pending);
            pending.clear();
        }
        for (String name : toLoad) {
            if (loaded.containsKey(name)) continue;
            java.util.function.Supplier<ToolCallback> supplier = suppliers.get(name);
            if (supplier == null) continue; // dropped between request and apply — defensive
            ToolCallback cb = supplier.get();
            if (cb == null) continue;
            loaded.put(name, cb);
            loadedCount++;
        }
        return loadedCount;
    }

    /** True if the tool's callback is currently in the loaded set. */
    public boolean isLoaded(String name) {
        return loaded.containsKey(name);
    }

    /** True if the tool is queued but not yet loaded. */
    public boolean isPendingLoad(String name) {
        return pending.contains(name);
    }

    /** Snapshot of currently-loaded tool names, in load order. */
    public List<String> loadedNames() {
        synchronized (loaded) {
            return List.copyOf(loaded.keySet());
        }
    }

    /** Snapshot of currently-loaded callbacks, in load order. */
    public List<ToolCallback> loadedCallbacks() {
        synchronized (loaded) {
            return List.copyOf(loaded.values());
        }
    }

    /** Snapshot of pending names. */
    public List<String> pendingNames() {
        return List.copyOf(pending);
    }

    /** Number of tools currently loaded. */
    public int loadedCount() {
        return loaded.size();
    }

    /** Number of tools pending. */
    public int pendingCount() {
        return pending.size();
    }

    /** The catalog this loader is bound to. */
    public ToolCatalog catalog() {
        return catalog;
    }

    /**
     * Drop a previously-loaded tool — useful when the harness wants to
     * cap the tool budget or rotate tools. Returns the unloaded callback.
     */
    public java.util.Optional<ToolCallback> unload(String name) {
        if (name == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(loaded.remove(name));
    }

    /** Remove every loaded and pending entry — a clean slate. */
    public void reset() {
        synchronized (loaded) {
            loaded.clear();
        }
        pending.clear();
    }
}
