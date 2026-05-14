package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads {@code SKILL.md} files from disk into a {@link SkillCatalog}. Walks
 * a configured set of directories — typical setup:
 *
 * <pre>
 * loader.scan(SkillScope.ENTERPRISE, Path.of("/etc/swarm/skills"));
 * loader.scan(SkillScope.PERSONAL,   Path.of(System.getProperty("user.home"), ".swarm/skills"));
 * loader.scan(SkillScope.PROJECT,    projectRoot.resolve(".swarm/skills"));
 * </pre>
 *
 * <p>Each {@code SKILL.md} file is parsed via
 * {@link SkillDefinition#fromSkillMd(String)} and registered with the
 * supplied {@link SkillCatalog}. The scope tags entries so {@link SkillCatalog}'s
 * precedence rules apply (PROJECT &gt; PERSONAL &gt; PLUGIN &gt; ENTERPRISE).
 *
 * <p>This is a one-shot loader; for live change detection see
 * {@link SkillLoader#refresh()} which re-scans every registered directory.
 * A watch-service-driven incremental updater is captured as a follow-up.
 */
@PublicApi(since = "1.0.24")
public final class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final SkillCatalog catalog;
    private final List<ScanRoot> scanRoots = new ArrayList<>();

    public SkillLoader(SkillCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /**
     * Register a directory to scan under the supplied scope. Returns
     * {@code this} for chaining. Non-existent directories are recorded but
     * skipped on scan; this is intentional so deployment configuration can
     * predeclare paths that may or may not exist at runtime.
     */
    public SkillLoader scan(SkillScope scope, Path directory) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        scanRoots.add(new ScanRoot(scope, directory.toAbsolutePath().normalize()));
        return this;
    }

    /** Walk every registered directory and register every {@code SKILL.md} found. Returns the count loaded. */
    public int loadAll() {
        int loaded = 0;
        for (ScanRoot root : scanRoots) {
            loaded += loadOne(root.scope, root.directory);
        }
        return loaded;
    }

    /** Re-scan every registered directory. Equivalent to {@link #loadAll()} — re-registers overwrite. */
    public int refresh() {
        return loadAll();
    }

    /** Discover skills under a single directory + scope. Returns the count registered. */
    public int loadOne(SkillScope scope, Path directory) {
        if (!Files.isDirectory(directory)) {
            log.debug("Skill directory absent or not a directory: {}", directory);
            return 0;
        }
        int registered = 0;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().equalsIgnoreCase("SKILL.md")) continue;
                try {
                    SkillCatalogEntry entry = readEntry(p, scope);
                    if (entry != null) {
                        catalog.register(entry);
                        registered++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to read skill at {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate skill directory {}: {}", directory, e.getMessage());
        }
        return registered;
    }

    /** Visible for testing: parse a single SKILL.md path into a catalog entry. */
    SkillCatalogEntry readEntry(Path path, SkillScope scope) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        SkillDefinition def = SkillDefinition.fromSkillMd(content);
        if (def.getName() == null || def.getName().isBlank()) {
            log.warn("Skipping SKILL.md without name at {}", path);
            return null;
        }
        if (def.getDescription() == null) {
            def.setDescription(""); // metadata requires non-null
        }
        SkillFrontmatter frontmatter = SkillFrontmatter.builder(scope)
                .whenToUse(def.getTriggerWhen())
                .extras(Map.of("loadedFrom", path.toString()))
                .build();
        return SkillCatalogEntry.from(def, frontmatter);
    }

    public List<ScanRoot> scanRoots() {
        return List.copyOf(scanRoots);
    }

    /** A registered scan target — scope + directory. */
    @PublicApi(since = "1.0.24")
    public record ScanRoot(SkillScope scope, Path directory) {
        public ScanRoot {
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(directory, "directory must not be null");
        }
    }
}
