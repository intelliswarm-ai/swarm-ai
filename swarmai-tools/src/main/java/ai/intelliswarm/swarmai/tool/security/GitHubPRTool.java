package ai.intelliswarm.swarmai.tool.security;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Creates a GitHub Pull Request with a patch/fix.
 * Requires GITHUB_TOKEN environment variable.
 *
 * <p>Permission: DANGEROUS (modifies external repository state).
 */
public class GitHubPRTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(GitHubPRTool.class);
    private static final String GITHUB_API = "https://api.github.com";
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() { return "github_create_pr"; }

    @Override
    public String getDescription() {
        return "Create a GitHub Pull Request. Provide 'owner' (repo owner), 'repo' (repo name), " +
               "'title' (PR title), 'body' (PR description), 'head' (source branch), " +
               "and 'base' (target branch, default 'main'). Requires GITHUB_TOKEN env var.";
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "owner", Map.of("type", "string", "description", "Repository owner"),
                "repo", Map.of("type", "string", "description", "Repository name"),
                "title", Map.of("type", "string", "description", "PR title"),
                "body", Map.of("type", "string", "description", "PR body/description"),
                "head", Map.of("type", "string", "description", "Source branch"),
                "base", Map.of("type", "string", "description", "Target branch (default: main)")
        );
    }

    @Override
    public String execute(Map<String, Object> params) {
        String owner = (String) params.getOrDefault("owner", "");
        String repo = (String) params.getOrDefault("repo", "");
        String title = (String) params.getOrDefault("title", "");
        String body = (String) params.getOrDefault("body", "");
        String head = (String) params.getOrDefault("head", "");
        String base = (String) params.getOrDefault("base", "main");

        if (owner.isBlank() || repo.isBlank() || title.isBlank() || head.isBlank()) {
            return "Error: 'owner', 'repo', 'title', and 'head' are required";
        }

        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            return "Error: GITHUB_TOKEN environment variable is not set";
        }

        logger.info("GitHubPRTool: creating PR on {}/{} from {} to {}", owner, repo, head, base);

        try {
            String url = GITHUB_API + "/repos/" + owner + "/" + repo + "/pulls";

            Map<String, String> prBody = Map.of(
                    "title", title,
                    "body", body,
                    "head", head,
                    "base", base
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            headers.set("Accept", "application/vnd.github+json");

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(prBody), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode pr = mapper.readTree(response.getBody());
                String prUrl = pr.path("html_url").asText();
                int prNumber = pr.path("number").asInt();

                logger.info("PR created: {} ({})", prUrl, prNumber);
                return "Pull Request created successfully!\nURL: " + prUrl + "\nNumber: #" + prNumber;
            } else {
                return "GitHub API returned: " + response.getStatusCode();
            }

        } catch (Exception e) {
            logger.error("Failed to create PR: {}", e.getMessage());
            return "PR creation failed: " + e.getMessage();
        }
    }
}
