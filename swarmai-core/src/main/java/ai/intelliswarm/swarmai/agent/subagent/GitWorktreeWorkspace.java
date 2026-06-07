package ai.intelliswarm.swarmai.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@link IsolatedWorkspace} backed by a <b>git worktree</b> branched off an
 * existing repository. Gives a coding-agent subagent the entire codebase as
 * read context plus a sandbox to write into, while keeping the parent repo
 * untouched. On close, the worktree and its ephemeral branch are removed.
 *
 * <h2>What it enables</h2>
 * <ul>
 *   <li><b>Skill creation:</b> Codex/Claude Code can read existing skills,
 *       follow conventions, write a new one with tests, and run the tests
 *       inside the worktree before the framework even sees the result.</li>
 *   <li><b>Self-improvement:</b> the framework can hand a coding agent a
 *       repaired-test task; the agent's edits land in the worktree; the
 *       framework runs {@code mvn test} there to validate.</li>
 *   <li><b>Diff-based outcome scoring:</b> {@link #diff()} returns the
 *       exact set of files the agent changed, suitable for review or for
 *       feeding back into the bandit reward model.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Constructor: validates the source repo, creates an ephemeral branch
 *       off {@code baseRef}, runs {@code git worktree add} in a temp directory.</li>
 *   <li>Use: {@link #root()} returns the worktree path; arbitrary git operations
 *       work inside it because it's a real working tree.</li>
 *   <li>{@link #close()}: {@code git worktree remove --force} +
 *       {@code git branch -D <ephemeral>}; falls back to recursive directory
 *       delete if those fail. Idempotent.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * Single-instance, single-threaded use is the supported pattern. {@code close()}
 * is synchronized; concurrent reads of {@link #root()} are safe.
 */
public final class GitWorktreeWorkspace implements IsolatedWorkspace {

    private static final Logger logger = LoggerFactory.getLogger(GitWorktreeWorkspace.class);

    /** Default cap for individual git invocations. */
    public static final Duration DEFAULT_GIT_TIMEOUT = Duration.ofSeconds(30);

    private final Path repoPath;
    private final String baseRef;
    private final String branchName;
    private final Path tempParent;
    private final Path worktreePath;
    private final Duration gitTimeout;
    private volatile boolean open;

    /** Worktree off HEAD with a default git timeout. */
    public GitWorktreeWorkspace(Path repoPath) throws IOException {
        this(repoPath, "HEAD", DEFAULT_GIT_TIMEOUT);
    }

    /** Worktree off the given ref. */
    public GitWorktreeWorkspace(Path repoPath, String baseRef) throws IOException {
        this(repoPath, baseRef, DEFAULT_GIT_TIMEOUT);
    }

    public GitWorktreeWorkspace(Path repoPath, String baseRef, Duration gitTimeout) throws IOException {
        this.repoPath = Objects.requireNonNull(repoPath, "repoPath").toAbsolutePath().normalize();
        this.baseRef = Objects.requireNonNull(baseRef, "baseRef");
        this.gitTimeout = Objects.requireNonNull(gitTimeout, "gitTimeout");
        if (baseRef.isBlank()) throw new IllegalArgumentException("baseRef must not be blank");

        validateRepo();
        validateRef();

        this.branchName = "swarmai-wt-" + UUID.randomUUID().toString().substring(0, 8);
        this.tempParent = Files.createTempDirectory("swarmai-wt-parent-");
        this.worktreePath = tempParent.resolve("worktree").toAbsolutePath();

        try {
            createWorktree();
        } catch (IOException e) {
            // best-effort cleanup of the temp parent if creation failed
            deleteRecursive(tempParent);
            throw e;
        }
        this.open = true;
    }

    @Override
    public Path root() {
        return worktreePath;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;

        // 1. git worktree remove
        try {
            GitResult r = runGit(repoPath, gitTimeout,
                    "worktree", "remove", "--force", worktreePath.toString());
            if (!r.ok()) {
                logger.warn("git worktree remove failed (exit {}): {}", r.exitCode, r.stderr.trim());
            }
        } catch (IOException e) {
            logger.warn("git worktree remove threw: {}", e.getMessage());
        }

        // 2. delete the ephemeral branch
        try {
            GitResult r = runGit(repoPath, gitTimeout,
                    "branch", "-D", branchName);
            if (!r.ok()) {
                // Non-fatal — the worktree remove may have already cleaned this up
                logger.debug("git branch -D {} non-zero: {}", branchName, r.stderr.trim());
            }
        } catch (IOException e) {
            logger.debug("git branch -D threw: {}", e.getMessage());
        }

        // 3. fallback: ensure the temp parent is gone
        deleteRecursive(tempParent);
    }

    // ============== Diff discovery ==============

    /**
     * Files changed in the worktree compared to {@code baseRef}, with line
     * counts where parseable. Empty list if no changes (or if git fails).
     *
     * <p>Includes <b>untracked</b> new files as ADDED — by default {@code git diff}
     * skips untracked files, but we run {@code git add -N -- .} first
     * (intent-to-add) so newly-written files surface. This is non-destructive:
     * intent-to-add doesn't stage content, just makes git aware of the file.
     */
    public List<FileChange> diff() {
        if (!open) {
            throw new IllegalStateException("workspace is closed");
        }
        // Make untracked files visible to `git diff` without staging their content.
        markUntrackedAsIntentToAdd();

        // First: name-status to get the kind of change
        List<FileChange> kindOnly = runDiffNameStatus();
        if (kindOnly.isEmpty()) return List.of();

        // Second: numstat to get line counts; merge into the kind-only list
        return mergeNumstat(kindOnly, runDiffNumstat());
    }

    private void markUntrackedAsIntentToAdd() {
        try {
            // -N marks paths as "intent to add" — listed in diff as new without staging content.
            // We pass `--` and `.` explicitly so it never tries to add the .git directory itself.
            runGit(worktreePath, gitTimeout, "add", "-N", "--", ".");
        } catch (IOException e) {
            // Non-fatal: if this fails the diff just won't include untracked files.
            logger.debug("git add -N failed (untracked files may be missing from diff): {}", e.getMessage());
        }
    }

    /**
     * The full unified diff against {@code baseRef}. Empty string if no
     * changes. Suitable for surfacing to a human reviewer or persisting as
     * an audit artifact.
     */
    public String diffAsPatch() {
        if (!open) {
            throw new IllegalStateException("workspace is closed");
        }
        // Mirror diff(): make untracked files visible to `git diff` first.
        // Without this, newly created files are silently absent from the
        // patch text — diff()/hasChanges() report them but the patch is
        // incomplete, hiding generated files from audit/review workflows.
        markUntrackedAsIntentToAdd();
        try {
            GitResult r = runGit(worktreePath, gitTimeout,
                    "diff", baseRef);
            return r.ok() ? r.stdout : "";
        } catch (IOException e) {
            logger.warn("diffAsPatch failed: {}", e.getMessage());
            return "";
        }
    }

    /** True if the worktree has any uncommitted changes vs {@code baseRef}. */
    public boolean hasChanges() {
        return !diff().isEmpty();
    }

    // ============== Accessors ==============

    public Path repoPath() { return repoPath; }
    public String baseRef() { return baseRef; }
    public String branchName() { return branchName; }

    // ============== Internals ==============

    private void validateRepo() throws IOException {
        if (!Files.isDirectory(repoPath)) {
            throw new IllegalArgumentException("repoPath does not exist or is not a directory: " + repoPath);
        }
        GitResult r = runGit(repoPath, gitTimeout, "rev-parse", "--git-dir");
        if (!r.ok()) {
            throw new IllegalArgumentException(
                    "repoPath is not a git repository: " + repoPath + " (git: " + r.stderr.trim() + ")");
        }
    }

    private void validateRef() throws IOException {
        GitResult r = runGit(repoPath, gitTimeout, "rev-parse", "--verify", "--quiet", baseRef);
        if (!r.ok()) {
            throw new IllegalArgumentException("unknown baseRef '" + baseRef + "' in repo " + repoPath);
        }
    }

    private void createWorktree() throws IOException {
        GitResult r = runGit(repoPath, gitTimeout,
                "worktree", "add", "-b", branchName, worktreePath.toString(), baseRef);
        if (!r.ok()) {
            throw new IOException("git worktree add failed: " + r.stderr.trim());
        }
    }

    private List<FileChange> runDiffNameStatus() {
        try {
            GitResult r = runGit(worktreePath, gitTimeout, "diff", "--name-status", baseRef);
            if (!r.ok()) {
                logger.warn("diff --name-status failed: {}", r.stderr.trim());
                return List.of();
            }
            return parseNameStatus(r.stdout);
        } catch (IOException e) {
            logger.warn("diff --name-status threw: {}", e.getMessage());
            return List.of();
        }
    }

    private List<FileChange> runDiffNumstat() {
        try {
            GitResult r = runGit(worktreePath, gitTimeout, "diff", "--numstat", baseRef);
            if (!r.ok()) {
                logger.debug("diff --numstat failed: {}", r.stderr.trim());
                return List.of();
            }
            return parseNumstat(r.stdout);
        } catch (IOException e) {
            logger.debug("diff --numstat threw: {}", e.getMessage());
            return List.of();
        }
    }

    /** Parse {@code git diff --name-status <ref>} output into FileChange records (no line counts yet). */
    static List<FileChange> parseNameStatus(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<FileChange> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\t");
            if (parts.length < 2) continue;
            String code = parts[0];
            ChangeKind kind = ChangeKind.fromGitCode(code);
            if (kind == ChangeKind.RENAMED && parts.length >= 3) {
                Path from = Paths.get(parts[1]);
                Path to = Paths.get(parts[2]);
                out.add(FileChange.renamed(from, to, -1, -1));
            } else {
                out.add(FileChange.of(Paths.get(parts[1]), kind));
            }
        }
        return out;
    }

    /** Parse {@code git diff --numstat <ref>} into a list ordered by line. */
    static List<FileChange> parseNumstat(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<FileChange> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\t");
            if (parts.length < 3) continue;
            int added = parseStat(parts[0]);
            int removed = parseStat(parts[1]);
            // For renames, numstat outputs a {old => new} fragment; normalize to the new path.
            String pathStr = parts[parts.length - 1];
            Path path = Paths.get(extractRenameTarget(pathStr));
            out.add(FileChange.of(path, ChangeKind.OTHER /* placeholder; merged below */,
                    added, removed));
        }
        return out;
    }

    private static int parseStat(String s) {
        if ("-".equals(s)) return -1;     // git's binary-file marker
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    /** "{old => new}" → "new"; plain path → unchanged. */
    static String extractRenameTarget(String pathStr) {
        int arrow = pathStr.indexOf("=>");
        if (arrow < 0) return pathStr;
        // Format: "prefix{old => new}suffix" or "old => new"
        int openBrace = pathStr.indexOf("{");
        int closeBrace = pathStr.indexOf("}");
        if (openBrace >= 0 && closeBrace > openBrace) {
            String prefix = pathStr.substring(0, openBrace);
            String suffix = closeBrace + 1 < pathStr.length() ? pathStr.substring(closeBrace + 1) : "";
            String inner = pathStr.substring(arrow + 2, closeBrace).trim();
            return (prefix + inner + suffix).trim();
        }
        return pathStr.substring(arrow + 2).trim();
    }

    /** Merge name-status (kinds) with numstat (line counts) on path equality. */
    static List<FileChange> mergeNumstat(List<FileChange> kindOnly, List<FileChange> numstat) {
        if (numstat.isEmpty()) return kindOnly;
        List<FileChange> out = new ArrayList<>(kindOnly.size());
        for (FileChange k : kindOnly) {
            FileChange merged = k;
            for (FileChange n : numstat) {
                if (n.path().equals(k.path())) {
                    merged = new FileChange(k.path(), k.kind(),
                            n.linesAdded(), n.linesRemoved(), k.renamedFrom());
                    break;
                }
            }
            out.add(merged);
        }
        return out;
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            logger.debug("recursive delete of {} failed: {}", root, e.getMessage());
        }
    }

    /** Internal holder for git invocation results. */
    private record GitResult(int exitCode, String stdout, String stderr) {
        boolean ok() { return exitCode == 0; }
    }

    /** Run {@code git <args>} with the given working directory, capturing stdout + stderr. */
    private static GitResult runGit(Path cwd, Duration timeout, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd.toFile());

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to start: git " + String.join(" ", args), e);
        }

        // Read stdout + stderr concurrently to avoid pipe-buffer deadlock.
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = proc.getInputStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = proc.getErrorStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        try {
            if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                throw new IOException("git " + Arrays.toString(args) + " timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("git invocation interrupted", e);
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        return new GitResult(proc.exitValue(), stdout, stderr);
    }
}
