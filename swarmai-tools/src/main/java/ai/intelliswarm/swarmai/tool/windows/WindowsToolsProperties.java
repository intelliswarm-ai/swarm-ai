package ai.intelliswarm.swarmai.tool.windows;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Windows PC-control tools. Bound from
 * {@code swarmai.tools.windows.*}.
 *
 * <p>Defaults are deliberately conservative: a small allowlist of user-owned
 * directories, dry-run on for mutations, and an aggressive process denylist.
 */
@ConfigurationProperties(prefix = "swarmai.tools.windows")
public class WindowsToolsProperties {

    /** Master switch for the whole windows tool category. */
    private boolean enabled = false;

    /** Approval timeout used by SupervisedMutationGuard for these tools. */
    private Duration approvalTimeout = Duration.ofMinutes(5);

    /** Directory the AuditLogger writes JSONL files to. {@code ${...}} placeholders are expanded. */
    private String auditDir = "${user.home}/.swarmai/audit";

    /**
     * If true, replace the supervised approval guard with an auto-approve guard:
     * every mutation is allowed without prompting. Audit log still records each
     * op and the kill-switch still aborts. Demo / unattended use only.
     */
    private boolean autoApprove = false;

    private final FileSystem filesystem = new FileSystem();
    private final Process process = new Process();
    private final Window window = new Window();
    private final Screenshot screenshot = new Screenshot();

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
    public Window getWindow() { return window; }
    public Screenshot getScreenshot() { return screenshot; }

    public static class FileSystem {
        /**
         * Roots that may be read or modified. Empty means: no roots → tool is
         * effectively disabled. Path strings may include {@code ${user.home}}
         * which will be expanded at construction time.
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
        /** Process names that may never be killed, regardless of approval. */
        private List<String> denylist = new ArrayList<>(List.of(
                "system", "smss", "csrss", "wininit", "winlogon", "services",
                "lsass", "svchost", "dwm", "explorer", "fontdrvhost"));

        /** Per-PowerShell-invocation timeout for process ops. */
        private Duration timeout = Duration.ofSeconds(15);

        public List<String> getDenylist() { return denylist; }
        public void setDenylist(List<String> denylist) { this.denylist = denylist; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Window {
        /** Per-PowerShell-invocation timeout for window ops. */
        private Duration timeout = Duration.ofSeconds(10);
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Screenshot {
        /** Directory where captures are written. {@code ${...}} placeholders are expanded. */
        private String outputDir = "${java.io.tmpdir}/swarmai-screenshots";
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    }
}
