
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static String executeTool(
            String toolName,
            String argumentsJson) throws Exception {

        JsonNode arguments = MAPPER.readTree(argumentsJson);

        JsonNode pathNode = arguments.get("file_path");

        if (pathNode == null || !pathNode.isTextual()) {
            throw new IllegalArgumentException(
                    "Missing or invalid file_path");
        }

        String filePath = pathNode.asText();

        if (filePath.isBlank()) {
            throw new IllegalArgumentException("file_path cannot be empty");
        }

        Path path = Path.of(filePath);

        switch (toolName) {

            case "Read": {
                return Files.readString(path);
            }

            case "Write": {
                JsonNode contentNode = arguments.get("content");

                if (contentNode == null || !contentNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid content");
                }

                String content = contentNode.asText();

                Path parent = path.toAbsolutePath().getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // Creates the file if missing and overwrites if present.
                Files.writeString(path, content);

                return "File written successfully.";
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

        List<Map<String, Object>> tools = List.of(
                readTool(),
                writeTool());

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
                .putAdditionalBodyProperty(
                        "tools",
                        JsonValue.from(tools));

        // Agent loop
        while (true) {

            ChatCompletion response = client.chat().completions().create(
                    paramsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("No choices in response");
            }

            var message = response.choices().get(0).message();

            // Preserve assistant response.
            paramsBuilder.addMessage(message);

            var toolCalls = message.toolCalls().orElse(List.of());

            // Model finished.
            if (toolCalls.isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
            }

            // Execute requested tools.
            for (var toolCall : toolCalls) {

                var function = toolCall.function();

                String toolName = function.name();

                String result = executeTool(
                        toolName,
                        function.arguments());

                // Return tool result to the model.
                paramsBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.id())
                                .content(result)
                                .build());
            }
        }
    }
}
