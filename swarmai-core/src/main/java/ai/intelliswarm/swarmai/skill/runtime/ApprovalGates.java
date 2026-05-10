package ai.intelliswarm.swarmai.skill.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Concrete implementations of {@link ImageApprovalGate}.
 *
 * <p>Each implementation handles one decision-making strategy:
 * <ul>
 *   <li>{@link AutoApprovalGate} — always APPROVE; the framework's default
 *       (so existing tests and offline pipelines don't break).</li>
 *   <li>{@link DenyGate} — always DENY; useful for "container skills are
 *       disabled" deployments.</li>
 *   <li>{@link ConsoleApprovalGate} — prompts on stdin/stdout. Right for
 *       interactive demos and self-improving CLI workflows.</li>
 *   <li>{@link CallbackApprovalGate} — wraps an arbitrary lambda; mostly
 *       used in tests and embedded UIs that have their own approval UX.</li>
 *   <li>{@link RememberingApprovalGate} — wraps another gate and
 *       persists APPROVE_AND_REMEMBER decisions to disk so subsequent
 *       runs skip the prompt for the same image.</li>
 * </ul>
 */
public final class ApprovalGates {

    private ApprovalGates() {}

    // =====================================================================
    // AutoApprovalGate
    // =====================================================================

    /** Always returns APPROVE — preserves backward compatibility. */
    public static final class AutoApprovalGate implements ImageApprovalGate {
        @Override public Decision approve(Request request) { return Decision.APPROVE; }
    }

    // =====================================================================
    // DenyGate
    // =====================================================================

    /** Always returns DENY. Useful for "container skills disabled" deployments. */
    public static final class DenyGate implements ImageApprovalGate {
        @Override public Decision approve(Request request) { return Decision.DENY; }
    }

    // =====================================================================
    // CallbackApprovalGate
    // =====================================================================

    /**
     * Adapter that wraps a {@code Function<Request, Decision>} lambda.
     * Lets tests and embedding UIs supply their own approval logic without
     * implementing the full interface.
     */
    public static final class CallbackApprovalGate implements ImageApprovalGate {
        private final Function<Request, Decision> callback;

        public CallbackApprovalGate(Function<Request, Decision> callback) {
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        @Override public Decision approve(Request request) {
            return Objects.requireNonNullElse(callback.apply(request), Decision.DENY);
        }
    }

    // =====================================================================
    // ConsoleApprovalGate
    // =====================================================================

    /**
     * Interactive console gate. Prints a summary of the image to stdout
     * and reads a y/n/r decision on stdin:
     * <ul>
     *   <li>{@code y} — APPROVE this build only.</li>
     *   <li>{@code r} — APPROVE_AND_REMEMBER (persisted across runs when
     *       paired with {@link RememberingApprovalGate}).</li>
     *   <li>{@code n} or anything else — DENY.</li>
     * </ul>
     *
     * <h3>Note on non-TTY environments</h3>
     * If stdin is closed or returns null (CI, daemonised process), the
     * gate falls back to DENY rather than auto-approving. This is the
     * safer default — silent CI runs shouldn't pull arbitrary images
     * just because no human was at the keyboard.
     */
    public static final class ConsoleApprovalGate implements ImageApprovalGate {
        private final PrintStream out;
        private final BufferedReader in;

        public ConsoleApprovalGate() {
            this(System.out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }

        public ConsoleApprovalGate(PrintStream out, BufferedReader in) {
            this.out = out;
            this.in = in;
        }

        @Override public Decision approve(Request request) {
            out.println();
            out.println("=========================================================");
            out.println(" Container image approval requested");
            out.println("=========================================================");
            out.println(" Action     : " + (request.isBuild() ? "BUILD" : "PULL"));
            out.println(" Image tag  : " + request.imageTag());
            out.println(" Base image : " + request.baseImage());
            out.println(" Source     : " + request.source());
            if (request.isBuild()) {
                out.println();
                out.println(" Dockerfile (truncated to 1500 chars):");
                String dockerfile = request.dockerfile();
                if (dockerfile.length() > 1500) dockerfile = dockerfile.substring(0, 1497) + "...";
                for (String line : dockerfile.split("\n", -1)) {
                    out.println("   | " + line);
                }
            }
            out.println();
            out.print(" [y]es / [r]emember / [n]o > ");
            out.flush();
            String reply;
            try {
                reply = in.readLine();
            } catch (IOException e) {
                out.println(" (stdin error: " + e.getMessage() + ") — denying");
                return Decision.DENY;
            }
            if (reply == null) {
                out.println(" (no stdin available) — denying");
                return Decision.DENY;
            }
            String norm = reply.trim().toLowerCase();
            return switch (norm) {
                case "y", "yes" -> Decision.APPROVE;
                case "r", "remember" -> Decision.APPROVE_AND_REMEMBER;
                default -> Decision.DENY;
            };
        }
    }

    // =====================================================================
    // RememberingApprovalGate
    // =====================================================================

    /**
     * Caching wrapper. Reads/writes a persistent approval list at
     * {@code ~/.swarmai/approved-images.txt}, one image-tag per line. When
     * an image is on the list, the wrapper skips the inner gate and
     * returns APPROVE immediately. When the inner gate returns
     * APPROVE_AND_REMEMBER, the wrapper appends the tag to the file.
     *
     * <p>The persisted list survives JVM restarts so a self-improving
     * loop that ran yesterday doesn't re-prompt for every tool today.
     *
     * <p>Per-session approvals (regular APPROVE) are also cached
     * in-memory to avoid re-prompting within the same JVM run.
     */
    public static final class RememberingApprovalGate implements ImageApprovalGate {
        private static final Logger logger = LoggerFactory.getLogger(RememberingApprovalGate.class);

        private final ImageApprovalGate inner;
        private final Path storeFile;
        private final Set<String> persistent;
        private final Set<String> sessionCache = ConcurrentHashMap.newKeySet();

        public RememberingApprovalGate(ImageApprovalGate inner) {
            this(inner, defaultStoreFile());
        }

        public RememberingApprovalGate(ImageApprovalGate inner, Path storeFile) {
            this.inner = Objects.requireNonNull(inner, "inner");
            this.storeFile = Objects.requireNonNull(storeFile, "storeFile");
            this.persistent = loadPersisted(storeFile);
        }

        private static Path defaultStoreFile() {
            String home = System.getProperty("user.home", ".");
            return Path.of(home, ".swarmai", "approved-images.txt");
        }

        @Override public Decision approve(Request request) {
            String key = request.imageTag();
            if (persistent.contains(key) || sessionCache.contains(key)) {
                return Decision.APPROVE;
            }
            Decision d = inner.approve(request);
            if (d == Decision.APPROVE_AND_REMEMBER) {
                if (persistent.add(key)) {
                    persist(key);
                }
            } else if (d == Decision.APPROVE) {
                sessionCache.add(key);
            }
            return d;
        }

        private static Set<String> loadPersisted(Path file) {
            Set<String> set = new HashSet<>();
            if (!Files.exists(file)) return set;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed);
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not read approval store {}: {}", file, e.getMessage());
            }
            return set;
        }

        private void persist(String entry) {
            try {
                Files.createDirectories(storeFile.getParent());
                Files.writeString(storeFile, entry + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Could not persist approval for {}: {}", entry, e.getMessage());
            }
        }

        /** For tests: clear the in-memory caches (does not touch the disk store). */
        public void clearSessionCache() { sessionCache.clear(); }
    }
}
