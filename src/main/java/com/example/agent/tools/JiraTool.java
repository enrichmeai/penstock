package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Jira integration tool. Supports searching issues by JQL and fetching individual issues.
 * Configuration is optional; if baseUrl or token are blank, the tool returns an error.
 */
@Component
public class JiraTool implements Tool {

    private final String baseUrl;
    private final String token;
    private final WebClient webClient;

    public JiraTool(AgentProperties props, WebClient.Builder webClientBuilder) {
        this.baseUrl = props.getTools().getJira().getBaseUrl();
        this.token = props.getTools().getJira().getToken();
        this.webClient = webClientBuilder.clone().build();
    }

    @Override
    public String name() {
        return "jira_search";
    }

    @Override
    public String description() {
        return "Search and retrieve Jira issues. Operations: 'search' (JQL query, returns keys + summaries), " +
               "'get' (fetch one issue by key). Requires agent.tools.jira.base-url and .token to be configured.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of("search", "get"),
                    "description", "Operation: 'search' or 'get'"),
                "query", Map.of("type", "string",
                    "description", "JQL query string for search, or issue key for get (e.g., 'PROJ-123')"),
                "limit", Map.of("type", "integer", "description", "Max results for search (default 10)", "default", 10)
            ),
            "required", List.of("type", "query")
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        // Check if Jira is configured
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            return ToolResult.error(callId,
                "Jira tool is not configured. Set agent.tools.jira.base-url and .token in application.yml");
        }

        String type = (String) args.get("type");
        String query = (String) args.get("query");
        int limit = toInt(args.get("limit"), 10);

        try {
            return switch (type) {
                case "search" -> searchIssues(callId, query, limit);
                case "get" -> getIssue(callId, query);
                default -> ToolResult.error(callId, "Unknown operation: " + type);
            };
        } catch (Exception e) {
            return ToolResult.error(callId, "Jira request failed: " + e.getMessage());
        }
    }

    private ToolResult searchIssues(String callId, String jql, int limit) {
        try {
            // Build the search URL with JQL and field parameters
            String searchUrl = String.format("%s/rest/api/3/search?jql=%s&maxResults=%d&fields=key,summary,status,assignee,updated",
                baseUrl,
                urlEncode(jql),
                Math.min(limit, 50)  // Cap at 50 to avoid excessive API load
            );

            var response = webClient.get()
                .uri(searchUrl)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();

            if (response == null || !response.containsKey("issues")) {
                return ToolResult.error(callId, "No issues found or invalid response");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");

            StringBuilder result = new StringBuilder();
            result.append("Found ").append(issues.size()).append(" issue(s):\n\n");

            for (Map<String, Object> issue : issues) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                String key = (String) issue.get("key");
                String summary = fields != null ? (String) fields.get("summary") : "N/A";
                @SuppressWarnings("unchecked")
                Map<String, Object> status = fields != null ? (Map<String, Object>) fields.get("status") : null;
                String statusName = status != null ? (String) status.get("name") : "N/A";
                @SuppressWarnings("unchecked")
                Map<String, Object> assignee = fields != null ? (Map<String, Object>) fields.get("assignee") : null;
                String assigneeName = assignee != null ? (String) assignee.get("displayName") : "Unassigned";
                String updated = fields != null ? (String) fields.get("updated") : "N/A";

                result.append(String.format("%s [%s] %s | %s | Updated: %s%n",
                    key, statusName, summary, assigneeName, updated.substring(0, Math.min(10, updated.length()))));
            }

            return ToolResult.ok(callId, result.toString());
        } catch (Exception e) {
            return ToolResult.error(callId, "Search failed: " + e.getMessage());
        }
    }

    private ToolResult getIssue(String callId, String issueKey) {
        try {
            String getUrl = String.format("%s/rest/api/3/issue/%s?fields=key,summary,description,status,assignee,updated",
                baseUrl, issueKey);

            var response = webClient.get()
                .uri(getUrl)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();

            if (response == null) {
                return ToolResult.error(callId, "Issue not found: " + issueKey);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) response.get("fields");
            String key = (String) response.get("key");
            String summary = fields != null ? (String) fields.get("summary") : "N/A";
            String description = fields != null ? (String) fields.get("description") : "No description";
            @SuppressWarnings("unchecked")
            Map<String, Object> status = fields != null ? (Map<String, Object>) fields.get("status") : null;
            String statusName = status != null ? (String) status.get("name") : "N/A";
            @SuppressWarnings("unchecked")
            Map<String, Object> assignee = fields != null ? (Map<String, Object>) fields.get("assignee") : null;
            String assigneeName = assignee != null ? (String) assignee.get("displayName") : "Unassigned";
            String updated = fields != null ? (String) fields.get("updated") : "N/A";

            StringBuilder result = new StringBuilder();
            result.append(String.format("Key: %s%n", key));
            result.append(String.format("Summary: %s%n", summary));
            result.append(String.format("Status: %s%n", statusName));
            result.append(String.format("Assignee: %s%n", assigneeName));
            result.append(String.format("Updated: %s%n", updated));
            result.append(String.format("Description: %s%n", description));

            return ToolResult.ok(callId, result.toString());
        } catch (Exception e) {
            return ToolResult.error(callId, "Get failed: " + e.getMessage());
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignore) {}
        }
        return def;
    }
}
