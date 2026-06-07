package ai.intelliswarm.swarmai.tool.os;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the cross-platform OS tools. Bound from
 * {@code swarmai.tools.os.*}.
 *
 * <p>Defaults match the historical {@code swarmai.tools.windows.*} defaults so
 * that the {@code desktop-tidy-windows} example and any other consumer of the
 * cross-platform name behaves identically on Windows, macOS, and Linux. The
 * {@code ${user.home}} placeholder is expanded by {@code PathGuard} at
 * construction time and works on every supported OS.
 */
@ConfigurationProperties(prefix = "swarmai.tools.os")
public class OsToolsProperties {

    /** Master switch for the whole cross-platform tool category. */
    private boolean enabled = false;

    /** Approval timeout used by the supervised mutation guard. */
    private Duration approvalTimeout = Duration.ofMinutes(5);

    /** Directory the AuditLogger writes JSONL files to. {@code ${...}} placeholders are expanded. */
    private String auditDir = "${user.home}/.swarmai/audit";

    /**
     * If true, replace the supervised approval guard with an auto-approve guard:
     * every mutation is allowed without prompting. The audit log still records
     * each op and the kill-switch still aborts. Demo / unattended use only.
     */
    private boolean autoApprove = false;

    private final FileSystem filesystem = new FileSystem();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getApprovalTimeout() { return approvalTimeout; }
    public void setApprovalTimeout(Duration approvalTimeout) { this.approvalTimeout = approvalTimeout; }
    public String getAuditDir() { return auditDir; }
    public void setAuditDir(String auditDir) { this.auditDir = auditDir; }
    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }
    public FileSystem getFilesystem() { return filesystem; }

    public static class FileSystem {
        /**
         * Roots that may be read or modified. Empty means: no roots → tool is
         * effectively disabled. Path strings may include {@code ${user.home}}
         * which will be expanded at construction time. Defaults are the same
         * three user-owned directories every OS exposes by default.
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
}
