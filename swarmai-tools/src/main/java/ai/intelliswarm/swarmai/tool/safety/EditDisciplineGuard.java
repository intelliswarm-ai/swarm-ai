package ai.intelliswarm.swarmai.tool.safety;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces harness-level invariants for safe file edits, so
 * an LLM can't blindly mutate files it hasn't seen. Three checks compose:
 *
 * <ol>
 *   <li><b>Read-before-Edit</b> — the file must have been read in the current
 *       session before {@link #applyEdit} is allowed. Without this an LLM can
 *       overwrite a file with data based on stale assumptions about its
 *       contents.</li>
 *   <li><b>Unique-match</b> — the {@code oldString} to be replaced must occur
 *       <em>exactly once</em> in the file (unless {@code replaceAll} is true).
 *       Catches the failure mode where an edit silently rewrites the wrong
 *       location because the LLM picked an ambiguous anchor.</li>
 *   <li><b>Stale-read</b> — when {@link #recordRead} is called with the file's
 *       hash and the file changes between the read and the edit, the edit
 *       fails with {@link StaleReadException}. Optional — if you don't supply
 *       a hash on read, staleness isn't checked.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * The internal map is a {@link ConcurrentHashMap}. Reads and writes are safe
 * across threads — typical when one tool registers a read and another applies
 * the edit (e.g. a multi-agent workflow).
 *
 * <h2>Composition with other tool-safety primitives</h2>
 * Distinct from {@link MutationGuard} (which gates side-effecting tool calls
 * via approval policies). The two are complementary: {@code MutationGuard}
 * answers "is the user OK with this mutation?", {@code EditDisciplineGuard}
 * answers "is the LLM mutating from a position of knowledge?". Wire both:
 * the mutation guard checks intent, the edit-discipline guard checks footing.
 */
public final class EditDisciplineGuard {

    private final Map<String, ReadRecord> readPaths = new ConcurrentHashMap<>();

    /** Record that {@code path} was read in this session. No staleness tracking. */
    public void recordRead(Path path) {
        recordRead(path, null);
    }

    /**
     * Record a read with the file's content (or a hash thereof). Staleness is
     * checked at edit time: if the file's content at edit time differs from
     * the recorded content, the edit fails with {@link StaleReadException}.
     *
     * @param path     normalized via {@link Path#toAbsolutePath()}
     * @param content  the file content captured at read time, or null to skip
     *                 staleness checking for this read
     */
    public void recordRead(Path path, String content) {
        Objects.requireNonNull(path, "path");
        readPaths.put(normalize(path), new ReadRecord(System.currentTimeMillis(), content));
    }

    /** Convenience overload accepting a string path. */
    public void recordRead(String path) {
        recordRead(Paths.get(Objects.requireNonNull(path, "path")));
    }

    /** Convenience overload accepting a string path and content. */
    public void recordRead(String path, String content) {
        recordRead(Paths.get(Objects.requireNonNull(path, "path")), content);
    }

    /** True if the path has been read in this session. */
    public boolean hasReadRecord(Path path) {
        Objects.requireNonNull(path, "path");
        return readPaths.containsKey(normalize(path));
    }

    /** Snapshot of every path read so far, normalized to absolute strings. */
    public Set<String> readPaths() {
        return Collections.unmodifiableSet(readPaths.keySet());
    }

    /** Drop a single path from the read set (e.g. after an explicit refresh). */
    public Optional<ReadRecord> forgetRead(Path path) {
        Objects.requireNonNull(path, "path");
        return Optional.ofNullable(readPaths.remove(normalize(path)));
    }

    /** Drop every recorded read — typically called between sessions. */
    public void clear() {
        readPaths.clear();
    }

    /**
     * Validate and apply an edit to {@code currentContent} of the file at
     * {@code path}. Throws on any of the three discipline violations.
     *
     * @param path           the file the edit targets
     * @param currentContent the file's content at edit time, freshly read
     * @param oldString      the substring to replace
     * @param newString      the replacement (may be empty for deletions)
     * @param replaceAll     when true, allow {@code oldString} to occur multiple
     *                       times and replace every occurrence
     * @return the new content with the edit applied
     */
    public String applyEdit(Path path, String currentContent,
                             String oldString, String newString, boolean replaceAll) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(currentContent, "currentContent");
        Objects.requireNonNull(oldString, "oldString");
        Objects.requireNonNull(newString, "newString");
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty");
        }

        // Check 1: read-before-edit
        ReadRecord record = readPaths.get(normalize(path));
        if (record == null) {
            throw new ReadBeforeEditException(path);
        }

        // Check 2: stale-read (only when we have a recorded snapshot)
        if (record.content != null && !record.content.equals(currentContent)) {
            throw new StaleReadException(path);
        }

        // Check 3: unique-match (or explicit opt-in to multi-replace)
        int matches = countOccurrences(currentContent, oldString);
        if (matches == 0) {
            throw new NoMatchException(path, oldString);
        }
        if (matches > 1 && !replaceAll) {
            throw new AmbiguousMatchException(path, oldString, matches);
        }

        // Apply
        String updated = replaceAll
                ? currentContent.replace(oldString, newString)
                : replaceFirstLiteral(currentContent, oldString, newString);

        // Re-record the read at the new content so a follow-up edit also
        // satisfies the discipline without requiring another read tool call.
        readPaths.put(normalize(path), new ReadRecord(System.currentTimeMillis(), updated));
        return updated;
    }

    /** Count of non-overlapping occurrences of {@code needle} in {@code haystack}. */
    public static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Literal (non-regex) replace-first. */
    static String replaceFirstLiteral(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) return haystack;
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }

    /** Internal: per-path metadata captured at read time. */
    public record ReadRecord(long readAtEpochMs, String content) {
        public ReadRecord {
            // content may be null when staleness tracking is disabled
        }
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    // =================================================================
    // Exception types — each carries enough context for the LLM to recover
    // when surfaced as a tool error string.
    // =================================================================

    /** Thrown when {@code applyEdit} is called on a path that was never read. */
    public static class ReadBeforeEditException extends RuntimeException {
        private final Path path;
        public ReadBeforeEditException(Path path) {
            super("Edit refused: " + path + " has not been read in this session. "
                    + "Read the file first, then retry the edit.");
            this.path = path;
        }
        public Path path() { return path; }
    }

    /** Thrown when the file content has changed since it was last read. */
    public static class StaleReadException extends RuntimeException {
        private final Path path;
        public StaleReadException(Path path) {
            super("Edit refused: " + path + " has changed since it was last read. "
                    + "Re-read the file, then retry the edit.");
            this.path = path;
        }
        public Path path() { return path; }
    }

    /** Thrown when {@code oldString} doesn't appear in the file. */
    public static class NoMatchException extends RuntimeException {
        private final Path path;
        private final String oldString;
        public NoMatchException(Path path, String oldString) {
            super("Edit refused: oldString not found in " + path + ". "
                    + "Re-read the file and verify the exact text to replace.");
            this.path = path;
            this.oldString = oldString;
        }
        public Path path() { return path; }
        public String oldString() { return oldString; }
    }

    /** Thrown when {@code oldString} appears more than once and {@code replaceAll} is false. */
    public static class AmbiguousMatchException extends RuntimeException {
        private final Path path;
        private final String oldString;
        private final int matchCount;
        public AmbiguousMatchException(Path path, String oldString, int matchCount) {
            super("Edit refused: oldString matched " + matchCount + " locations in " + path
                    + ". Either provide a longer oldString that uniquely identifies one "
                    + "location, or pass replaceAll=true to update every occurrence.");
            this.path = path;
            this.oldString = oldString;
            this.matchCount = matchCount;
        }
        public Path path() { return path; }
        public String oldString() { return oldString; }
        public int matchCount() { return matchCount; }
    }

    /** A single recorded interaction. Map-backed so order doesn't matter for semantics. */
    @SuppressWarnings("unused")
    private static Map<String, ReadRecord> linkedHashMapForOrderedDiagnostics() {
        return Collections.synchronizedMap(new LinkedHashMap<>());
    }
}
