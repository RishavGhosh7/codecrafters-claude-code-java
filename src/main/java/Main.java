
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

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
                                                "description",
                                                "The path to the file to read")),
                                "required", List.of("file_path"))));
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

        // Advertise the Read tool to the LLM.
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
                .putAdditionalBodyProperty(
                        "tools",
                        JsonValue.from(List.of(readTool())))
                .build();

        // Send request.
        ChatCompletion response = client.chat().completions().create(params);

        if (response.choices().isEmpty()) {
            throw new RuntimeException("No choices in response");
        }

        var message = response.choices().get(0).message();

        // Check whether the model requested a tool call.
        var toolCalls = message.toolCalls().orElse(List.of());

        if (toolCalls.isEmpty()) {
            // Normal text response.
            System.out.print(message.content().orElse(""));
            return;
        }

        // Execute only the first tool call.
        // var toolCall = toolCalls.get(0).asFunction();
        // var function = toolCall.function();

        var toolCall = toolCalls.get(0);
        var function = toolCall.function();
        String toolName = function.name();

        if (!"Read".equals(toolName)) {
            throw new IllegalArgumentException(
                    "Unknown tool: " + toolName);
        }

        // Parse tool arguments.
        JsonNode arguments = MAPPER.readTree(
                function.arguments());

        JsonNode filePathNode = arguments.get("file_path");

        if (filePathNode == null || !filePathNode.isTextual()) {
            throw new IllegalArgumentException(
                    "Missing or invalid file_path argument");
        }

        String filePath = filePathNode.asText();

        // Execute Read.
        String contents = Files.readString(
                Path.of(filePath));

        // Output exact file contents.
        System.out.print(contents);
    }
}
