package ai.intelliswarm.swarmai.selfimproving.reporter;

import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reports framework improvements back to the GitHub repository by creating
 * branches and pull requests via the GitHub API.
 *
 * This is how the 10% community investment flows back to the base:
 *
 *   Enterprise deployment runs workflow
 *     → 10% phase produces ImprovementProposal
 *       → GitHubImprovementReporter creates PR with evidence
 *         → CI validates (test suite)
 *           → Tier 1: auto-merge if tests pass
 *           → Tier 2: human reviews and approves
 *
 * The PR body includes:
 * - What changed (the generic improvement)
 * - Why we believe it (the specific origin with evidence)
 * - Cross-validation results (proof it's generic)
 * - Expected impact (tokens saved, quality delta)
 * - Who benefits on upgrade (workflow shape match criteria)
 */
public class GitHubImprovementReporter {

    private static final Logger log = LoggerFactory.getLogger(GitHubImprovementReporter.class);

    private final String repoOwner;
    private final String repoName;
    private final String apiToken;
    private final String baseBranch;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubImprovementReporter(String repoOwner, String repoName,
                                      String apiToken, String baseBranch) {
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.apiToken = apiToken;
        this.baseBranch = baseBranch;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Report an improvement proposal by creating a branch and PR.
     *
     * @return the PR URL if created, or empty if skipped/failed
     */
    public Optional<String> report(ImprovementProposal proposal) {
        if (apiToken == null || apiToken.isBlank()) {
            log.debug("No GitHub API token configured, skipping PR creation");
            return Optional.empty();
        }

        try {
            String branchName = buildBranchName(proposal);
            String prTitle = buildPrTitle(proposal);
            String prBody = buildPrBody(proposal);
            Map<String, String> fileChanges = buildFileChanges(proposal);

            if (fileChanges.isEmpty()) {
                log.debug("No file changes for proposal {}, skipping", proposal.proposalId());
                return Optional.empty();
            }

            // Step 1: Get base branch SHA
            String baseSha = getBaseBranchSha();
            if (baseSha == null) return Optional.empty();

            // Step 2: Create branch
            boolean branchCreated = createBranch(branchName, baseSha);
            if (!branchCreated) return Optional.empty();

            // Step 3: Commit file changes
            boolean committed = commitChanges(branchName, fileChanges, buildCommitMessage(proposal));
            if (!committed) return Optional.empty();

            // Step 4: Create PR
            String prUrl = createPullRequest(branchName, prTitle, prBody, proposal.tier());
            if (prUrl != null) {
                log.info("Created improvement PR: {} → {}", branchName, prUrl);

                // Step 5: Add labels
                addLabels(prUrl, proposal);
            }

            return Optional.ofNullable(prUrl);

        } catch (Exception e) {
            log.error("Failed to create improvement PR for proposal {}: {}",
                    proposal.proposalId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Report multiple proposals as a single batched PR.
     */
    public Optional<String> reportBatch(List<ImprovementProposal> proposals) {
        if (proposals.isEmpty()) return Optional.empty();
        if (apiToken == null || apiToken.isBlank()) return Optional.empty();

        try {
            String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String branchName = "self-improving/batch-" + date;
            String prTitle = buildBatchPrTitle(proposals);
            String prBody = buildBatchPrBody(proposals);

            // Merge all file changes
            Map<String, String> allChanges = new LinkedHashMap<>();
            for (ImprovementProposal p : proposals) {
                allChanges.putAll(buildFileChanges(p));
            }

            if (allChanges.isEmpty()) return Optional.empty();

            String baseSha = getBaseBranchSha();
            if (baseSha == null) return Optional.empty();

            createBranch(branchName, baseSha);
            commitChanges(branchName, allChanges, "feat(self-improving): batch intelligence update " + date);

            ImprovementTier highestTier = proposals.stream()
                    .map(ImprovementProposal::tier)
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(ImprovementTier.TIER_2_REVIEW);

            String prUrl = createPullRequest(branchName, prTitle, prBody, highestTier);
            if (prUrl != null) {
                log.info("Created batch improvement PR with {} proposals: {}", proposals.size(), prUrl);
            }
            return Optional.ofNullable(prUrl);

        } catch (Exception e) {
            log.error("Failed to create batch improvement PR: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- Branch name and PR content builders ---

    private String buildBranchName(ImprovementProposal proposal) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String category = proposal.rule().category().name().toLowerCase().replace("_", "-");
        return "self-improving/%s-%s".formatted(category, date);
    }

    private String buildPrTitle(ImprovementProposal proposal) {
        String tierTag = switch (proposal.tier()) {
            case TIER_1_AUTOMATIC -> "[auto-merge]";
            case TIER_2_REVIEW -> "[review]";
            case TIER_3_PROPOSAL -> "[proposal]";
        };
        String category = proposal.rule().category().name().toLowerCase().replace("_", " ");
        return "%s self-improving: %s".formatted(tierTag, truncate(proposal.rule().recommendation(), 60));
    }

    private String buildBatchPrTitle(List<ImprovementProposal> proposals) {
        long t1 = proposals.stream().filter(p -> p.tier() == ImprovementTier.TIER_1_AUTOMATIC).count();
        long t2 = proposals.stream().filter(p -> p.tier() == ImprovementTier.TIER_2_REVIEW).count();
        return "self-improving: %d intelligence updates (%d auto, %d review)".formatted(
                proposals.size(), t1, t2);
    }

    String buildPrBody(ImprovementProposal proposal) {
        StringBuilder body = new StringBuilder();

        body.append("## Self-Improvement: Framework Intelligence Update\n\n");
        body.append("> Generated by the 10%% self-improvement phase. ");
        body.append("Every SwarmAI workflow invests 10%% of its token budget into improving the framework for all users.\n\n");

        // What changed
        body.append("### What changes\n\n");
        if (proposal.improvement() != null) {
            body.append("**Target**: `%s`\n".formatted(proposal.improvement().targetFile()));
            if (proposal.improvement().currentValue() != null) {
                body.append("**Current value**: `%s`\n".formatted(proposal.improvement().currentValue()));
            }
            if (proposal.improvement().proposedValue() != null) {
                body.append("**Proposed value**: `%s`\n".formatted(proposal.improvement().proposedValue()));
            }
            body.append("**Expected impact**: %s\n\n".formatted(proposal.improvement().expectedImpact()));
        }

        // Generic rule
        body.append("### Generic rule (applies to all matching workflows)\n\n");
        body.append("**Condition**: `%s`\n".formatted(proposal.rule().condition()));
        body.append("**Category**: %s\n".formatted(proposal.rule().category()));
        body.append("**Confidence**: %.0f%%\n\n".formatted(proposal.rule().confidence() * 100));

        // Specific origin (evidence)
        if (proposal.origin() != null) {
            body.append("### Evidence (specific origin)\n\n");
            body.append("- **Workflow type**: %s\n".formatted(proposal.origin().workflowType()));
            body.append("- **Observation**: %s\n".formatted(proposal.origin().observation()));
            body.append("- **Occurrences**: %d\n".formatted(proposal.origin().occurrenceCount()));
            if (proposal.origin().tokenSavings() > 0) {
                body.append("- **Token savings**: %,d per run\n".formatted(proposal.origin().tokenSavings()));
            }
            if (proposal.origin().qualityDelta() != 0) {
                body.append("- **Quality delta**: %+.2f\n".formatted(proposal.origin().qualityDelta()));
            }
            body.append("\n");
        }

        // Cross-validation
        if (proposal.rule().crossValidation() != null) {
            var cv = proposal.rule().crossValidation();
            body.append("### Cross-validation (proof it's generic)\n\n");
            body.append("- **Tested against**: %d other workflow executions\n".formatted(cv.testedAgainst()));
            body.append("- **Matched**: %d positive, %d negative\n".formatted(cv.matchedPositive(), cv.matchedNegative()));
            body.append("- **Score**: %.0f%%\n".formatted(cv.crossValidationScore() * 100));
            body.append("- **Result**: %s\n\n".formatted(cv.passed() ? "PASSED" : "FAILED"));
            if (!cv.validationDetails().isEmpty()) {
                for (String detail : cv.validationDetails()) {
                    body.append("  - %s\n".formatted(detail));
                }
                body.append("\n");
            }
        }

        // Who benefits
        body.append("### Who benefits on upgrade\n\n");
        body.append("Any workflow matching: `%s`\n\n".formatted(proposal.rule().condition()));

        // Tier info
        body.append("### Tier: %s\n\n".formatted(proposal.tier().getDescription()));
        if (proposal.tier() == ImprovementTier.TIER_1_AUTOMATIC) {
            body.append("> This is a Tier 1 (automatic) improvement. If CI passes, it can be auto-merged.\n\n");
        }

        body.append("---\n");
        body.append("*Generated by SwarmAI Self-Improvement Engine (10%% community investment)*\n");

        return body.toString();
    }

    private String buildBatchPrBody(List<ImprovementProposal> proposals) {
        StringBuilder body = new StringBuilder();
        body.append("## Self-Improvement: Batch Intelligence Update\n\n");
        body.append("> %d improvements from the community's 10%% self-improvement investment.\n\n".formatted(proposals.size()));

        body.append("### Summary\n\n");
        body.append("| # | Category | Confidence | Tier | Impact |\n");
        body.append("|---|---|---|---|---|\n");
        int i = 1;
        for (ImprovementProposal p : proposals) {
            body.append("| %d | %s | %.0f%% | %s | %s |\n".formatted(
                    i++,
                    p.rule().category().name(),
                    p.rule().confidence() * 100,
                    p.tier().name(),
                    p.improvement() != null ? p.improvement().expectedImpact() : "N/A"
            ));
        }

        body.append("\n### Details\n\n");
        for (ImprovementProposal p : proposals) {
            body.append("**%s** (confidence: %.0f%%)\n".formatted(
                    p.rule().recommendation(), p.rule().confidence() * 100));
            body.append("- Condition: `%s`\n".formatted(p.rule().condition()));
            if (p.origin() != null) {
                body.append("- Evidence: %s (%d occurrences)\n".formatted(
                        p.origin().observation(), p.origin().occurrenceCount()));
            }
            body.append("\n");
        }

        body.append("---\n");
        body.append("*Generated by SwarmAI Self-Improvement Engine (10%% community investment)*\n");
        return body.toString();
    }

    private Map<String, String> buildFileChanges(ImprovementProposal proposal) {
        Map<String, String> changes = new LinkedHashMap<>();

        if (proposal.improvement() == null) return changes;

        try {
            String targetFile = proposal.improvement().targetFile();
            if (targetFile.endsWith(".json")) {
                // Build a JSON entry for this improvement
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rule_id", proposal.rule().ruleId());
                entry.put("condition", proposal.rule().condition());
                entry.put("recommendation", proposal.rule().recommendation());
                if (proposal.rule().recommendedValue() != null) {
                    entry.put("recommended_value", proposal.rule().recommendedValue());
                }
                entry.put("confidence", proposal.rule().confidence());
                entry.put("created_at", proposal.createdAt().toString());

                String path = "swarmai-self-improving/src/main/resources/" + targetFile;
                changes.put(path, objectMapper.writeValueAsString(Map.of(
                        "_meta", Map.of(
                                "description", "Updated by self-improvement phase",
                                "version", "1.0.0-learned",
                                "last_updated", java.time.Instant.now().toString()
                        ),
                        "rules", List.of(entry)
                )));
            }
        } catch (Exception e) {
            log.warn("Failed to build file changes for proposal {}: {}", proposal.proposalId(), e.getMessage());
        }

        return changes;
    }

    private String buildCommitMessage(ImprovementProposal proposal) {
        return "feat(self-improving): %s\n\nCategory: %s\nConfidence: %.0f%%\nTier: %s\nCondition: %s\n\nGenerated by SwarmAI Self-Improvement Engine".formatted(
                truncate(proposal.rule().recommendation(), 60),
                proposal.rule().category(),
                proposal.rule().confidence() * 100,
                proposal.tier(),
                proposal.rule().condition()
        );
    }

    // --- GitHub API calls ---

    private String getBaseBranchSha() {
        try {
            HttpResponse<String> response = githubGet("/repos/%s/%s/git/ref/heads/%s".formatted(
                    repoOwner, repoName, baseBranch));
            if (response.statusCode() != 200) {
                log.error("Failed to get base branch SHA: {}", response.statusCode());
                return null;
            }
            Map<?, ?> json = objectMapper.readValue(response.body(), Map.class);
            Map<?, ?> object = (Map<?, ?>) json.get("object");
            return (String) object.get("sha");
        } catch (Exception e) {
            log.error("Failed to get base branch SHA: {}", e.getMessage());
            return null;
        }
    }

    private boolean createBranch(String branchName, String sha) {
        try {
            Map<String, String> body = Map.of(
                    "ref", "refs/heads/" + branchName,
                    "sha", sha
            );
            HttpResponse<String> response = githubPost(
                    "/repos/%s/%s/git/refs".formatted(repoOwner, repoName),
                    objectMapper.writeValueAsString(body));
            if (response.statusCode() == 201 || response.statusCode() == 422) {
                // 422 = branch already exists, which is fine for batching
                return true;
            }
            log.error("Failed to create branch {}: {}", branchName, response.statusCode());
            return false;
        } catch (Exception e) {
            log.error("Failed to create branch {}: {}", branchName, e.getMessage());
            return false;
        }
    }

    private boolean commitChanges(String branchName, Map<String, String> fileChanges, String message) {
        // For simplicity, use the Contents API (one file at a time)
        for (var entry : fileChanges.entrySet()) {
            try {
                String encodedContent = Base64.getEncoder()
                        .encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", message);
                body.put("content", encodedContent);
                body.put("branch", branchName);

                // Check if file exists (for update vs create)
                HttpResponse<String> existing = githubGet(
                        "/repos/%s/%s/contents/%s?ref=%s".formatted(
                                repoOwner, repoName, entry.getKey(), branchName));
                if (existing.statusCode() == 200) {
                    Map<?, ?> existingJson = objectMapper.readValue(existing.body(), Map.class);
                    body.put("sha", existingJson.get("sha"));
                }

                HttpResponse<String> response = githubPut(
                        "/repos/%s/%s/contents/%s".formatted(repoOwner, repoName, entry.getKey()),
                        objectMapper.writeValueAsString(body));

                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    log.error("Failed to commit {}: {}", entry.getKey(), response.statusCode());
                    return false;
                }
            } catch (Exception e) {
                log.error("Failed to commit {}: {}", entry.getKey(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    private String createPullRequest(String branchName, String title, String prBody, ImprovementTier tier) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", title);
            body.put("body", prBody);
            body.put("head", branchName);
            body.put("base", baseBranch);

            HttpResponse<String> response = githubPost(
                    "/repos/%s/%s/pulls".formatted(repoOwner, repoName),
                    objectMapper.writeValueAsString(body));

            if (response.statusCode() == 201) {
                Map<?, ?> json = objectMapper.readValue(response.body(), Map.class);
                return (String) json.get("html_url");
            }
            log.error("Failed to create PR: {} — {}", response.statusCode(), response.body());
            return null;
        } catch (Exception e) {
            log.error("Failed to create PR: {}", e.getMessage());
            return null;
        }
    }

    private void addLabels(String prUrl, ImprovementProposal proposal) {
        try {
            // Extract PR number from URL
            String prNumber = prUrl.substring(prUrl.lastIndexOf('/') + 1);
            List<String> labels = new ArrayList<>();
            labels.add("self-improving");
            labels.add(proposal.tier().name().toLowerCase().replace("_", "-"));
            labels.add("category:" + proposal.rule().category().name().toLowerCase().replace("_", "-"));

            if (proposal.tier() == ImprovementTier.TIER_1_AUTOMATIC) {
                labels.add("auto-merge");
            }

            Map<String, Object> body = Map.of("labels", labels);
            githubPost("/repos/%s/%s/issues/%s/labels".formatted(repoOwner, repoName, prNumber),
                    objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.debug("Failed to add labels to PR: {}", e.getMessage());
        }
    }

    // --- HTTP helpers ---

    private HttpResponse<String> githubGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> githubPost(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> githubPut(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
