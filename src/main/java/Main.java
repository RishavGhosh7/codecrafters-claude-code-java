
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // READ TOOL
    private static Map<String, Object> readTool() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Read",
                        "description", "Read and return the contents of a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "file_path", Map.of(
                                                "type", "string",
                                                "description", "The path to the file to read")),
                                "required", List.of("file_path"))));
    }

    // WRITE TOOL
    private static Map<String, Object> writeTool() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Write",
                        "description", "Write content to a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "file_path", Map.of(
                                                "type", "string",
                                                "description", "The path of the file to write to"),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "The content to write to the file")),
                                "required", List.of("file_path", "content"))));
    }

    // BASH TOOL
    private static Map<String, Object> bashTool() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Bash",
                        "description", "Execute a bash command",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "command", Map.of(
                                                "type", "string",
                                                "description", "The bash command to execute")),
                                "required", List.of("command"))));
    }

    // TOOL EXECUTION
    private static String executeTool(
            String toolName,
            String argumentsJson) throws Exception {

        JsonNode arguments = MAPPER.readTree(argumentsJson);

        switch (toolName) {

            case "Read": {
                JsonNode pathNode = arguments.get("file_path");

                if (pathNode == null || !pathNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid file_path");
                }

                String filePath = pathNode.asText();

                return Files.readString(Path.of(filePath));
            }

            case "Write": {
                JsonNode pathNode = arguments.get("file_path");
                JsonNode contentNode = arguments.get("content");

                if (pathNode == null || !pathNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid file_path");
                }

                if (contentNode == null || !contentNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid content");
                }

                String filePath = pathNode.asText();
                String content = contentNode.asText();

                Path path = Path.of(filePath);

                Path parent = path.toAbsolutePath().getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // Creates or overwrites the file.
                Files.writeString(path, content);

                return "File written successfully.";
            }

            case "Bash": {
                JsonNode commandNode = arguments.get("command");

                if (commandNode == null || !commandNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid command");
                }

                String command = commandNode.asText();

                ProcessBuilder processBuilder = new ProcessBuilder(
                        "bash", "-c", command);

                // Combine stdout and stderr.
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                String output;

                // Read command output.
                try (var input = process.getInputStream()) {
                    output = new String(
                            input.readAllBytes(),
                            StandardCharsets.UTF_8);
                }

                int exitCode = process.waitFor();

                // Include exit status for the agent.
                if (exitCode != 0) {
                    return output
                            + (output.isEmpty() ? "" : "\n")
                            + "Command exited with code "
                            + exitCode;
                }

                return output;
            }

            default:
                throw new IllegalArgumentException(
                        "Unknown tool: " + toolName);
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");

        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException(
                    "OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        // Advertise all tools.
        List<Map<String, Object>> tools = List.of(
                readTool(),
                writeTool(),
                bashTool());

        // Initialize conversation.
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
                .putAdditionalBodyProperty(
                        "tools",
                        JsonValue.from(tools));

        // AGENT LOOP
        while (true) {

            ChatCompletion response = client.chat().completions().create(
                    paramsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("No choices in response");
            }

            var message = response.choices().get(0).message();

            // Keep assistant response in conversation history.
            paramsBuilder.addMessage(message);

            var toolCalls = message.toolCalls().orElse(List.of());

            // Final response.
            if (toolCalls.isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
            }

            // Execute each requested tool.
            for (var toolCall : toolCalls) {

                var function = toolCall.function();

                String toolName = function.name();

                String result = executeTool(
                        toolName,
                        function.arguments());

                // Append tool result to conversation.
                paramsBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.id())
                                .content(result)
                                .build());
            }
        }
    }
}
