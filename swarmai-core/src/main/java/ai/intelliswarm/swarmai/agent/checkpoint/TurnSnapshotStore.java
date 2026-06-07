package ai.intelliswarm.swarmai.agent.checkpoint;

import ai.intelliswarm.swarmai.api.PublicApi;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Per-turn snapshot store backed by a shadow git repository. Every agent turn
 * that mutates the workspace can call {@link #snapshot(Path, String, String, int, Map)};
 * later turns can {@link #restore(Path, String)} the workspace to any prior
 * snapshot or {@link #diff(String, String)} two turns to inspect what changed.
 *
 * <p>Distinct from {@code GitWorktreeWorkspace} (per-task workspace
 * isolation): the snapshot store is <em>global</em>, automatic, and operates
 * on whatever directory the caller passes in. Distinct from {@code EventStore}
 * (event replay over a turn's logical events): the snapshot captures the
 * <em>filesystem state</em> after each turn, so an agent that has corrupted
 * the workspace can be wound back.
 *
 * <p>Storage layout (default {@code ~/.swarm/checkpoints/store/}):
 * <pre>
 * store/
 *   .git/                            ← shadow git repo
 *   workspace-{runId}/               ← per-run mirror of caller's workspace files
 *     (caller's files copied in on every snapshot)
 * </pre>
 *
 * <p>Each {@link #snapshot} call: copies the caller's workspace into the
 * mirror directory, stages all changes, and commits with a structured message
 * containing the turn index and human-readable description. JGit handles
 * content deduplication automatically — the same blob across runs is stored
 * once.
 *
 * <p>Optional dep: {@code org.eclipse.jgit:org.eclipse.jgit}. Construction
 * will throw if not on the classpath.
 */
@PublicApi(since = "1.0.24")
public final class TurnSnapshotStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnSnapshotStore.class);
    private static final String DEFAULT_AUTHOR_NAME = "swarmai-checkpoint";
    private static final String DEFAULT_AUTHOR_EMAIL = "checkpoint@intelliswarm.ai";

    private final Path storeRoot;
    private final Git git;

    public TurnSnapshotStore(Path storeRoot) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storeRoot);
            Path gitDir = this.storeRoot.resolve(".git");
            if (Files.exists(gitDir)) {
                this.git = Git.open(this.storeRoot.toFile());
            } else {
                this.git = Git.init().setDirectory(this.storeRoot.toFile()).call();
                // Empty initial commit so HEAD exists for subsequent operations.
                this.git.commit()
                        .setMessage("init")
                        .setAllowEmpty(true)
                        .setAuthor(new PersonIdent(DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL))
                        .call();
            }
        } catch (IOException | GitAPIException e) {
            throw new SnapshotStoreException("Failed to init turn-snapshot store at " + storeRoot, e);
        }
    }

    public Path storeRoot() {
        return storeRoot;
    }

    /**
     * Snapshot the {@code workspaceRoot} after a turn. Files are mirrored into
     * {@code store/workspace-{runId}/}, staged, and committed. Returns the
     * resulting {@link TurnSnapshot}.
     */
    public TurnSnapshot snapshot(Path workspaceRoot, String runId, String message,
                                 int turnIndex, Map<String, Object> attributes) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        Path mirror = storeRoot.resolve("workspace-" + sanitize(runId));
        try {
            // Clear the mirror's previous contents so files removed from the
            // source workspace don't ghost forward into later commits. Without
            // this step `copyTree` only overwrites files that still exist,
            // leaving stale tracked files behind — `git status` would never
            // see the deletion. Recreate the dir afterwards so the subsequent
            // copyTree has a target.
            clearDirectoryContents(mirror);
            Files.createDirectories(mirror);
            copyTree(workspaceRoot.toAbsolutePath().normalize(), mirror);

            // Two-pass add to capture every transition: new/modified files
            // (default add) and deletions of previously-tracked files
            // (setUpdate(true) — JGit's equivalent of `git add -u`).
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            String fullMessage = String.format("[run=%s turn=%d] %s", runId, turnIndex, message);
            RevCommit commit = git.commit()
                    .setMessage(fullMessage)
                    .setAllowEmpty(true)
                    .setAuthor(new PersonIdent(DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL))
                    .call();
            return new TurnSnapshot(commit.getName(), runId, turnIndex, message, Instant.now(), attributes);
        } catch (IOException | GitAPIException e) {
            throw new SnapshotStoreException("snapshot failed for run=" + runId + " turn=" + turnIndex, e);
        }
    }

    /**
     * Restore the {@code targetWorkspace} to the state captured at
     * {@code commitId}. The runId is parsed from the commit's structured
     * message ({@code [run=X turn=N] body}) to identify the right
     * {@code workspace-<runId>} mirror — critical when the store holds
     * multiple concurrent runs, since picking the first mirror alphabetically
     * (the old behaviour) could replay a different run's files.
     */
    public void restore(Path targetWorkspace, String commitId) {
        Objects.requireNonNull(targetWorkspace, "targetWorkspace must not be null");
        Objects.requireNonNull(commitId, "commitId must not be null");
        try {
            git.checkout().setName(commitId).call();

            // Parse the commit message to learn which run this snapshot belongs to.
            // The init commit (no [run=...] prefix) has no associated workspace.
            String runId;
            try (Repository repo = git.getRepository(); RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(repo.resolve(commitId));
                ParsedMessage pm = parseMessage(commit.getFullMessage().trim());
                runId = pm != null ? pm.runId : null;
            }

            if (runId == null) {
                throw new SnapshotStoreException(
                        "Cannot restore commit " + commitId
                                + ": message lacks [run=...] header (likely the init commit)", null);
            }

            Path mirror = storeRoot.resolve("workspace-" + sanitize(runId));
            if (!Files.exists(mirror)) {
                throw new SnapshotStoreException(
                        "Cannot restore commit " + commitId
                                + ": mirror " + mirror.getFileName() + " not present at this commit", null);
            }
            Path target = targetWorkspace.toAbsolutePath().normalize();
            // Clear the target before copying so files added after the snapshot
            // (and absent from the mirror) are removed. Without this step the
            // restored workspace is a strict superset of the snapshot, leaving
            // ghost artifacts that downstream turns would mistakenly observe.
            clearDirectoryContents(target);
            Files.createDirectories(target);
            copyTree(mirror, target);
        } catch (GitAPIException | IOException e) {
            throw new SnapshotStoreException("restore failed for commit " + commitId, e);
        }
    }

    /** Unified diff between two snapshots. Returns a text patch. */
    public String diff(String fromCommitId, String toCommitId) {
        Objects.requireNonNull(fromCommitId, "fromCommitId must not be null");
        Objects.requireNonNull(toCommitId, "toCommitId must not be null");
        try (Repository repo = git.getRepository();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out);
             RevWalk walk = new RevWalk(repo)) {
            fmt.setRepository(repo);
            RevCommit fromCommit = walk.parseCommit(repo.resolve(fromCommitId));
            RevCommit toCommit = walk.parseCommit(repo.resolve(toCommitId));
            CanonicalTreeParser fromTree = new CanonicalTreeParser();
            CanonicalTreeParser toTree = new CanonicalTreeParser();
            try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
                fromTree.reset(reader, fromCommit.getTree().getId());
                toTree.reset(reader, toCommit.getTree().getId());
            }
            List<DiffEntry> diffs = fmt.scan(fromTree, toTree);
            for (DiffEntry de : diffs) {
                fmt.format(de);
            }
            return out.toString();
        } catch (IOException e) {
            throw new SnapshotStoreException("diff failed " + fromCommitId + ".." + toCommitId, e);
        }
    }

    /** Recent snapshots in reverse chronological order. */
    public List<TurnSnapshot> recent(int limit) {
        if (limit <= 0) limit = 20;
        List<TurnSnapshot> out = new ArrayList<>();
        try {
            Iterable<RevCommit> log = git.log().setMaxCount(limit).call();
            for (RevCommit c : log) {
                String fullMessage = c.getFullMessage().trim();
                ParsedMessage pm = parseMessage(fullMessage);
                if (pm == null) continue;
                out.add(new TurnSnapshot(c.getName(), pm.runId, pm.turnIndex, pm.body,
                        Instant.ofEpochSecond(c.getCommitTime()), Map.of()));
            }
        } catch (GitAPIException e) {
            throw new SnapshotStoreException("log enumeration failed", e);
        }
        return List.copyOf(out);
    }

    @Override
    public void close() {
        if (git != null) git.close();
    }

    // ---------- helpers ----------

    private static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) return;
        Files.createDirectories(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Delete every entry under {@code dir} but leave {@code dir} itself in place.
     * Used by {@link #snapshot} to ensure stale files from previous turns don't
     * persist in the mirror — the git index needs to see deletions to record
     * them in the commit.
     */
    private static void clearDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            // Reverse order so files are deleted before their parent directories.
            walk.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static ParsedMessage parseMessage(String fullMessage) {
        // Expected format: "[run=foo turn=3] message body"
        if (!fullMessage.startsWith("[run=")) return null;
        int closeBracket = fullMessage.indexOf(']');
        if (closeBracket < 0) return null;
        String header = fullMessage.substring(1, closeBracket);
        int turnIdx = header.indexOf("turn=");
        if (turnIdx < 0) return null;
        String runId = header.substring("run=".length(), header.indexOf(' '));
        try {
            int turnIndex = Integer.parseInt(header.substring(turnIdx + "turn=".length()));
            String body = fullMessage.substring(closeBracket + 1).trim();
            return new ParsedMessage(runId, turnIndex, body);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record ParsedMessage(String runId, int turnIndex, String body) {}

    @PublicApi(since = "1.0.24")
    public static final class SnapshotStoreException extends RuntimeException {
        public SnapshotStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
