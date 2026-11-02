
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

        // Maintain conversation history across iterations.
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
                .putAdditionalBodyProperty(
                        "tools",
                        JsonValue.from(List.of(readTool())));

        // Agent loop.
        while (true) {

            ChatCompletion response = client.chat().completions().create(
                    paramsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("No choices in response");
            }

            var message = response.choices().get(0).message();

            // Record the assistant response in conversation history.
            paramsBuilder.addMessage(message);

            var toolCalls = message.toolCalls().orElse(List.of());

            // No tool calls: this is the final response.
            if (toolCalls.isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
            }

            // Execute every tool call returned in this response.
            for (var toolCall : toolCalls) {

                var function = toolCall.function();
                String toolName = function.name();

                if (!"Read".equals(toolName)) {
                    throw new IllegalArgumentException(
                            "Unknown tool: " + toolName);
                }

                // Parse arguments.
                JsonNode arguments = MAPPER.readTree(
                        function.arguments());

                JsonNode filePathNode = arguments.get("file_path");

                if (filePathNode == null ||
                        !filePathNode.isTextual()) {
                    throw new IllegalArgumentException(
                            "Missing or invalid file_path argument");
                }

                String filePath = filePathNode.asText();

                // Execute Read tool.
                String result = Files.readString(
                        Path.of(filePath));

                // Send tool result back to the model.
                paramsBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.id())
                                .content(result)
                                .build());
            }

            // Continue loop.
            // The model receives the tool results in the next request.
        }
    }
}
