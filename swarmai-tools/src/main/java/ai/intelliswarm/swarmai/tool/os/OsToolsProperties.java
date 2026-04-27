package ai.intelliswarm.swarmai.tool.os;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the cross-platform OS-control tools. Bound from {@code swarmai.tools.os.*}.
 *
 * <p>Defaults are deliberately conservative: a small allowlist of user-owned directories,
 * dry-run on for mutations, and a denylist that covers system processes on all three OSes
 * (Windows, macOS, Linux) so an over-eager agent can't kill them on any platform.
 */
@ConfigurationProperties(prefix = "swarmai.tools.os")
public class OsToolsProperties {

    /** Master switch for the cross-platform OS tool category. */
    private boolean enabled = false;

    /** Approval timeout used by SupervisedMutationGuard for these tools. */
    private Duration approvalTimeout = Duration.ofMinutes(5);

    /** Directory the AuditLogger writes JSONL files to. {@code ${...}} placeholders expand. */
    private String auditDir = "${user.home}/.swarmai/audit";

    /**
     * If true, replace the supervised approval guard with an auto-approve guard:
     * every mutation is allowed without prompting. Audit log still records each
     * op and the kill-switch still aborts. Demo / unattended use only.
     */
    private boolean autoApprove = false;

    private final FileSystem filesystem = new FileSystem();
    private final Process process = new Process();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getApprovalTimeout() { return approvalTimeout; }
    public void setApprovalTimeout(Duration approvalTimeout) { this.approvalTimeout = approvalTimeout; }
    public String getAuditDir() { return auditDir; }
    public void setAuditDir(String auditDir) { this.auditDir = auditDir; }
    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }
    public FileSystem getFilesystem() { return filesystem; }
    public Process getProcess() { return process; }

    public static class FileSystem {
        /**
         * Roots that may be read or modified. Empty means: no roots → tool refuses every op.
         * The {@code ${user.home}} expansion gives the right path on every OS without
         * hard-coding a {@code C:\Users\…} or {@code /home/…} path.
         */
        private List<String> allowedRoots = new ArrayList<>(List.of(
                "${user.home}/Desktop",
                "${user.home}/Downloads",
                "${user.home}/Documents"));

        /** When true, mutating ops require {@code apply=true} explicitly to actually run. */
        private boolean dryRunDefault = true;

        /** Hard cap on files touched by a single op, to limit blast radius. */
        private int maxFilesPerOp = 200;

        public List<String> getAllowedRoots() { return allowedRoots; }
        public void setAllowedRoots(List<String> allowedRoots) { this.allowedRoots = allowedRoots; }
        public boolean isDryRunDefault() { return dryRunDefault; }
        public void setDryRunDefault(boolean dryRunDefault) { this.dryRunDefault = dryRunDefault; }
        public int getMaxFilesPerOp() { return maxFilesPerOp; }
        public void setMaxFilesPerOp(int maxFilesPerOp) { this.maxFilesPerOp = maxFilesPerOp; }
    }

    public static class Process {
        /**
         * Process names that may never be killed, regardless of approval. The default
         * union covers Windows, macOS, and Linux system processes so the same denylist
         * works everywhere — runtime denylist match is a substring check, so e.g.
         * {@code "system"} catches both Windows {@code System} and Linux {@code systemd}.
         */
        private List<String> denylist = new ArrayList<>(List.of(
                // Windows
                "system", "smss", "csrss", "wininit", "winlogon", "services",
                "lsass", "svchost", "dwm", "explorer", "fontdrvhost",
                // macOS
                "kernel_task", "launchd", "loginwindow", "WindowServer",
                // Linux
                "systemd", "init", "kthreadd"));

        /** Per-invocation timeout for process ops. */
        private Duration timeout = Duration.ofSeconds(15);

        public List<String> getDenylist() { return denylist; }
        public void setDenylist(List<String> denylist) { this.denylist = denylist; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }
}
