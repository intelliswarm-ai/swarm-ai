package ai.intelliswarm.swarmai.tool.discovery;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link CatalogEntry}s — the harness's view of every tool that
 * <em>could</em> be loaded for an agent. Insertion-ordered so listings are
 * deterministic.
 *
 * <p>Distinct from a Spring AI tool callback list: a catalog is metadata
 * (names, descriptions, keywords, schema summaries) the harness can show the
 * agent without actually attaching the tools to a {@code ChatClient}. Once
 * the agent searches the catalog and decides which tools it wants, the
 * harness wires those specific callbacks into the next call.
 *
 * <h2>Threading</h2>
 * Methods are synchronized — sufficient for typical write-once / read-many
 * usage. If your harness mutates the catalog mid-conversation (e.g. plugin
 * hot-load) the synchronization keeps reads consistent.
 */
public final class ToolCatalog {

    private final Map<String, CatalogEntry> entries = new LinkedHashMap<>();

    /** Register an entry, replacing any prior entry with the same name. */
    public synchronized void register(CatalogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.name(), entry);
    }

    /** Register an entry, throwing if the name is already taken. */
    public synchronized void registerStrict(CatalogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.containsKey(entry.name())) {
            throw new IllegalArgumentException(
                    "tool already catalogued: '" + entry.name() + "'");
        }
        entries.put(entry.name(), entry);
    }

    /** Look up an entry by name. */
    public synchronized Optional<CatalogEntry> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(entries.get(name));
    }

    /** True if a tool by this name is catalogued. */
    public synchronized boolean contains(String name) {
        return name != null && entries.containsKey(name);
    }

    /** Remove an entry. Returns the removed entry, if any. */
    public synchronized Optional<CatalogEntry> unregister(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(entries.remove(name));
    }

    /** All catalogued entries in registration order. Snapshot — safe to iterate. */
    public synchronized Collection<CatalogEntry> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(entries).values());
    }

    /** Number of catalogued tools. */
    public synchronized int size() {
        return entries.size();
    }

    /** True if no tools are catalogued. */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }
}
