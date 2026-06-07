package ai.intelliswarm.swarmai.tool.code.diff;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java unified diff between either two files or two literal strings.
 * Uses an LCS-based Myers-style algorithm — no shell-out, no native binary.
 *
 * <p>The agent reaches for this when it wants to <em>see</em> the difference
 * between {@code A} and {@code B} without running {@code git diff} (e.g. to
 * compare a fresh draft vs the existing file, or two versions in memory).
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
@Component
public class DiffTool implements BaseTool {

    private static final int DEFAULT_CONTEXT = 3;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    public DiffTool() { this(null); }

    public DiffTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "diff"; }

    @Override
    public String getDescription() {
        return "Show a unified diff between two files or two literal strings. Use either "
             + "{file_a, file_b} for on-disk paths or {text_a, text_b} for in-memory comparison. "
             + "Returns a standard unified diff with {context} lines of surrounding context (default 3).";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-diff"; }
    @Override public List<String> getTags() { return List.of("diff", "compare"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String fileA = (String) parameters.get("file_a");
        String fileB = (String) parameters.get("file_b");
        String textA = (String) parameters.get("text_a");
        String textB = (String) parameters.get("text_b");

        boolean haveFiles = fileA != null && fileB != null;
        boolean haveTexts = textA != null && textB != null;
        if (haveFiles == haveTexts) {
            return "Error: provide exactly one pair — either {file_a, file_b} or {text_a, text_b}";
        }

        String a;
        String b;
        String labelA;
        String labelB;
        try {
            if (haveFiles) {
                Path pa = resolveSafe(fileA);
                Path pb = resolveSafe(fileB);
                if (pa == null || pb == null) return "Error: refusing to read outside project root";
                a = readOrEmpty(pa);
                b = readOrEmpty(pb);
                labelA = "a/" + relativize(pa);
                labelB = "b/" + relativize(pb);
            } else {
                a = textA;
                b = textB;
                labelA = "a";
                labelB = "b";
            }
        } catch (IOException e) {
            return "Error reading inputs: " + e.getMessage();
        }

        int context = parameters.get("context") instanceof Number n
                ? Math.max(0, Math.min(20, n.intValue())) : DEFAULT_CONTEXT;

        String diff = unifiedDiff(a, b, labelA, labelB, context);
        if (diff.length() > MAX_OUTPUT_CHARS) {
            diff = diff.substring(0, MAX_OUTPUT_CHARS)
                 + "\n... [truncated]";
        }
        return diff.isEmpty() ? "(files are identical)" : diff;
    }

    private Path resolveSafe(String relPath) {
        if (relPath == null) return null;
        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path resolved = root.resolve(relPath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private String relativize(Path p) {
        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        return root.relativize(p).toString();
    }

    private static String readOrEmpty(Path p) throws IOException {
        if (!Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // --- unified diff implementation ------------------------------------

    static String unifiedDiff(String a, String b, String labelA, String labelB, int context) {
        if (a.equals(b)) return "";

        List<String> aLines = splitLines(a);
        List<String> bLines = splitLines(b);
        int[][] lcs = lcs(aLines, bLines);
        List<int[]> edits = backtrackEdits(lcs, aLines, bLines);

        StringBuilder out = new StringBuilder();
        out.append("--- ").append(labelA).append('\n');
        out.append("+++ ").append(labelB).append('\n');

        List<Hunk> hunks = computeHunks(edits, aLines, bLines, context);
        for (Hunk h : hunks) {
            out.append("@@ -").append(h.startA + 1).append(',').append(h.lenA)
               .append(" +").append(h.startB + 1).append(',').append(h.lenB)
               .append(" @@\n");
            for (String l : h.lines) out.append(l).append('\n');
        }
        return out.toString();
    }

    /** Each edit: op = 0 keep, -1 delete from a, +1 insert from b. */
    private record Edit(int op, String line, int idxA, int idxB) {}

    private static List<String> splitLines(String s) {
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(s.split("\n", -1)));
    }

    private static int[][] lcs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private static List<int[]> backtrackEdits(int[][] dp, List<String> a, List<String> b) {
        int i = a.size();
        int j = b.size();
        List<int[]> rev = new ArrayList<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
                rev.add(new int[]{0, i - 1, j - 1});
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                rev.add(new int[]{+1, i, j - 1});
                j--;
            } else {
                rev.add(new int[]{-1, i - 1, j});
                i--;
            }
        }
        java.util.Collections.reverse(rev);
        return rev;
    }

    private record Hunk(int startA, int lenA, int startB, int lenB, List<String> lines) {}

    private static List<Hunk> computeHunks(List<int[]> edits, List<String> a, List<String> b, int context) {
        // Mark positions where there's a change (op != 0).
        boolean[] changed = new boolean[edits.size()];
        for (int i = 0; i < edits.size(); i++) {
            if (edits.get(i)[0] != 0) changed[i] = true;
        }

        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < edits.size()) {
            if (!changed[i]) { i++; continue; }
            int start = Math.max(0, i - context);
            int end = i;
            // Extend the hunk while the gap between changes is <= 2*context.
            while (end < edits.size()) {
                int nextChange = -1;
                for (int k = end; k < edits.size(); k++) {
                    if (changed[k]) { nextChange = k; break; }
                }
                if (nextChange == -1) break;
                if (nextChange - end <= 2 * context) {
                    end = nextChange;
                } else {
                    break;
                }
                end++;
            }
            int finalEnd = Math.min(edits.size(), end + context);

            int startA = -1, startB = -1;
            int lenA = 0, lenB = 0;
            List<String> hLines = new ArrayList<>();
            for (int k = start; k < finalEnd; k++) {
                int[] e = edits.get(k);
                if (startA < 0) {
                    if (e[0] == +1) {
                        // pure insertion before any a-anchor → derive startA from previous keep/delete
                        startA = e[1];
                        startB = e[2];
                    } else {
                        startA = e[1];
                        startB = e[2];
                    }
                }
                switch (e[0]) {
                    case 0 -> {
                        hLines.add(" " + a.get(e[1]));
                        lenA++; lenB++;
                    }
                    case -1 -> {
                        hLines.add("-" + a.get(e[1]));
                        lenA++;
                    }
                    case +1 -> {
                        hLines.add("+" + b.get(e[2]));
                        lenB++;
                    }
                }
            }
            hunks.add(new Hunk(Math.max(0, startA), lenA, Math.max(0, startB), lenB, hLines));
            i = finalEnd;
        }
        return hunks;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_a", Map.of("type", "string",
                "description", "First file path (use with file_b)."));
        props.put("file_b", Map.of("type", "string",
                "description", "Second file path (use with file_a)."));
        props.put("text_a", Map.of("type", "string",
                "description", "First literal text (use with text_b)."));
        props.put("text_b", Map.of("type", "string",
                "description", "Second literal text (use with text_a)."));
        props.put("context", Map.of("type", "integer",
                "description", "Lines of context around each change (0..20, default 3)."));
        schema.put("properties", props);
        return schema;
    }
}
