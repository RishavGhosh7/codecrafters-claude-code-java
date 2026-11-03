
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Main {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path SKILLS_DIR = Path.of(".claude", "skills");

    private static final String MODEL = "anthropic/claude-haiku-4.5";

    private static final int MAX_TOOL_ITERATIONS = 20;

    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static void main(String[] args) {
        try {
            String prompt = parsePrompt(args);

            if (prompt == null || prompt.isBlank()) {
                System.err.println("Usage: your_program.sh -p <prompt>");
                System.exit(1);
            }

            String apiKey = System.getenv("OPENROUTER_API_KEY");

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "OPENROUTER_API_KEY is not set");
            }

            String baseUrl = System.getenv("OPENROUTER_BASE_URL");

            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openrouter.ai/api/v1";
            }

            baseUrl = baseUrl.replaceAll("/+$", "");

            List<Map<String, String>> skills = discoverSkills();

            String systemPrompt = buildSystemPrompt(skills);

            // Handle /skillname invocation.
            prompt = resolveSkillInvocation(prompt, skills);

            ArrayNode messages = mapper.createArrayNode();

            ObjectNode systemMessage = mapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);

            ObjectNode userMessage = mapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            runAgent(baseUrl, apiKey, messages);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String parsePrompt(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) && i + 1 < args.length) {
                return args[i + 1];
            }
        }

        return null;
    }

    private static List<Map<String, String>> discoverSkills()
            throws IOException {

        List<Map<String, String>> skills = new ArrayList<>();

        if (!Files.isDirectory(SKILLS_DIR)) {
            return skills;
        }

        try (var dirs = Files.list(SKILLS_DIR)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path skillFile = dir.resolve("SKILL.md");

                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }

                String content = Files.readString(
                        skillFile, StandardCharsets.UTF_8);

                String name = dir.getFileName().toString();
                String description = "";

                String[] lines = content.split("\\R");
                boolean inFrontmatter = false;

                for (int i = 0; i < lines.length; i++) {
                    if (i == 0 && lines[i].trim().equals("---")) {
                        inFrontmatter = true;
                        continue;
                    }

                    if (inFrontmatter && lines[i].trim().equals("---")) {
                        break;
                    }

                    if (!inFrontmatter) {
                        continue;
                    }

                    String line = lines[i].trim();

                    if (line.startsWith("name:")) {
                        name = line.substring(5).trim();
                    } else if (line.startsWith("description:")) {
                        description = line.substring(12).trim();
                    }
                }

                skills.add(Map.of(
                        "name", name,
                        "description", description,
                        "path", skillFile.toString()));
            }
        }

        return skills;
    }

    private static String buildSystemPrompt(
            List<Map<String, String>> skills) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are a helpful coding assistant.\n");

        sb.append("\nAvailable skills:\n");

        for (Map<String, String> skill : skills) {
            sb.append("- ")
                    .append(skill.get("name"))
                    .append(": ")
                    .append(skill.get("description"))
                    .append("\n");
        }

        sb.append("""

                When a user invokes a skill, follow the skill instructions.
                Use the Bash tool when shell commands are necessary.
                """);

        return sb.toString();
    }

    private static String resolveSkillInvocation(
            String prompt,
            List<Map<String, String>> skills) throws IOException {

        if (!prompt.startsWith("/")) {
            return prompt;
        }

        String skillName = prompt.substring(1).trim();

        // Invocation should contain only the skill name.
        if (skillName.isEmpty() || skillName.contains(" ")) {
            return prompt;
        }

        for (Map<String, String> skill : skills) {
            if (skill.get("name").equals(skillName)) {
                String content = Files.readString(
                        Path.of(skill.get("path")),
                        StandardCharsets.UTF_8);

                // Remove frontmatter and load only skill body.
                String[] lines = content.split("\\R", -1);

                if (lines.length > 0
                        && lines[0].trim().equals("---")) {

                    int end = -1;

                    for (int i = 1; i < lines.length; i++) {
                        if (lines[i].trim().equals("---")) {
                            end = i;
                            break;
                        }
                    }

                    if (end != -1) {
                        StringBuilder body = new StringBuilder();

                        for (int i = end + 1; i < lines.length; i++) {
                            body.append(lines[i]);

                            if (i < lines.length - 1) {
                                body.append("\n");
                            }
                        }

                        return body.toString().trim();
                    }
                }

                return content;
            }
        }

        return prompt;
    }

    private static ObjectNode buildBashTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = mapper.createObjectNode();
        function.put("name", "Bash");
        function.put("description",
                "Execute a bash shell command.");

        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode command = mapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "The command to execute.");

        properties.set("command", command);
        parameters.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("command");

        parameters.set("required", required);
        parameters.put("additionalProperties", false);

        function.set("parameters", parameters);
        tool.set("function", function);

        return tool;
    }

    private static void runAgent(
            String baseUrl,
            String apiKey,
            ArrayNode messages) throws Exception {

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {

            ObjectNode requestBody = mapper.createObjectNode();

            requestBody.put("model", MODEL);
            requestBody.set("messages", messages);

            ArrayNode tools = mapper.createArrayNode();
            tools.add(buildBashTool());

            requestBody.set("tools", tools);
            requestBody.put("tool_choice", "auto");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                throw new IOException(
                        "OpenRouter HTTP "
                                + response.statusCode()
                                + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());

            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                throw new IOException(
                        "No choices returned by model: "
                                + response.body());
            }

            JsonNode message = choices.get(0).path("message");

            JsonNode toolCalls = message.path("tool_calls");

            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                JsonNode content = message.get("content");

                if (content != null && !content.isNull()) {
                    if (content.isTextual()) {
                        System.out.println(content.asText());
                    } else {
                        System.out.println(content.toString());
                    }
                }

                return;
            }

            // Append the assistant message, including tool calls.
            ObjectNode assistantMessage = ((ObjectNode) message).deepCopy();

            assistantMessage.put("role", "assistant");
            messages.add(assistantMessage);

            for (JsonNode toolCall : toolCalls) {
                String toolCallId = toolCall.path("id").asText();

                String functionName = toolCall.path("function")
                        .path("name").asText();

                String arguments = toolCall.path("function")
                        .path("arguments").asText();

                if (!"Bash".equals(functionName)) {
                    addToolResult(
                            messages,
                            toolCallId,
                            "Unknown tool: " + functionName);
                    continue;
                }

                JsonNode parsedArgs = mapper.readTree(arguments);

                String command = parsedArgs.path("command").asText();

                String output = executeBash(command);

                addToolResult(messages, toolCallId, output);
            }
        }

        throw new IllegalStateException(
                "Maximum tool iterations reached.");
    }

    private static void addToolResult(
            ArrayNode messages,
            String toolCallId,
            String output) {

        ObjectNode toolMessage = mapper.createObjectNode();

        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCallId);
        toolMessage.put("content", output);

        messages.add(toolMessage);
    }

    private static String executeBash(String command)
            throws IOException, InterruptedException {

        Process process = new ProcessBuilder(
                "bash", "-lc", command)
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(
                60, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            process.waitFor();

            return "Error: command timed out after 60 seconds.";
        }

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        return output + "\nExit code: " + process.exitValue();
    }
}
