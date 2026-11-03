
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Main {

    private static final Path SKILLS_DIR = Path.of(".claude", "skills");

    private record Skill(
            String name,
            String description,
            Path file) {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || !"-p".equals(args[0])) {
                System.err.println("Usage: program -p <prompt>");
                System.exit(1);
            }

            String prompt = args[1];

            // Discover skills.
            List<Skill> skills = discoverSkills();

            // Build system instructions containing skill metadata.
            String systemPrompt = buildSkillsPrompt(skills);

            String userMessage = prompt;

            // Handle slash commands.
            if (prompt.trim().startsWith("/")) {

                String command = prompt.trim()
                        .substring(1)
                        .split("\\s+", 2)[0];

                if (!command.matches("[a-zA-Z0-9_-]+")) {
                    throw new IllegalArgumentException(
                            "Invalid skill name: " + command);
                }

                Skill selected = skills.stream()
                        .filter(s -> s.name().equals(command))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Skill not found: " + command));

                // Load ONLY the invoked skill's body.
                String skillContent = Files.readString(selected.file());

                String skillBody = extractSkillBody(skillContent);

                userMessage = skillBody;
            }

            // OpenRouter configuration.
            String apiKey = System.getenv("OPENROUTER_API_KEY");

            String baseUrl = System.getenv("OPENROUTER_BASE_URL");

            if (apiKey == null || apiKey.isBlank()) {
                throw new RuntimeException(
                        "OPENROUTER_API_KEY is not set");
            }

            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openrouter.ai/api/v1";
            }

            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model("anthropic/claude-haiku-4.5")
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(userMessage)
                    .build();

            ChatCompletion response = client.chat().completions().create(params);

            if (response.choices().isEmpty()) {
                throw new RuntimeException(
                        "No choices in response");
            }

            System.out.print(
                    response.choices()
                            .get(0)
                            .message()
                            .content()
                            .orElse(""));

        } catch (Exception e) {
            System.err.println(
                    "Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<Skill> discoverSkills()
            throws IOException {

        List<Skill> skills = new ArrayList<>();

        if (!Files.isDirectory(SKILLS_DIR)) {
            return skills;
        }

        try (Stream<Path> dirs = Files.list(SKILLS_DIR)) {

            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(
                            p -> p.getFileName().toString()))
                    .forEach(dir -> {

                        Path skillFile = dir.resolve("SKILL.md");

                        if (!Files.isRegularFile(skillFile)) {
                            return;
                        }

                        try {
                            String content = Files.readString(skillFile);

                            String frontmatter = extractFrontmatter(content);

                            String name = getField(frontmatter, "name");

                            String description = getField(frontmatter, "description");

                            if (name.isBlank()) {
                                name = dir.getFileName().toString();
                            }

                            if (!description.isBlank()) {
                                skills.add(new Skill(
                                        name,
                                        description,
                                        skillFile));
                            }

                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        return skills;
    }

    private static String buildSkillsPrompt(
            List<Skill> skills) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an AI coding assistant.

                You have access to the following skills.
                Each skill has a name and a description.

                When the user asks a question or describes a task,
                identify the skill whose description best matches
                the requested task.

                Use the skill descriptions to make your decision.
                Do not claim you lack context when a skill clearly
                matches the requested task.

                If the user asks which skill matches a task and
                requests only the skill name, return exactly the
                matching skill name without additional text,
                punctuation, or formatting.

                Available skills:

                """);

        for (Skill skill : skills) {
            sb.append("- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description())
                    .append("\n");
        }

        return sb.toString();
    }

    private static String extractFrontmatter(
            String content) {

        String normalized = content.replace("\r\n", "\n");

        if (!normalized.startsWith("---\n")) {
            return "";
        }

        int closing = normalized.indexOf("\n---", 4);

        if (closing == -1) {
            return "";
        }

        return normalized.substring(4, closing);
    }

    private static String getField(
            String frontmatter,
            String field) {

        Pattern pattern = Pattern.compile(
                "(?m)^" + Pattern.quote(field)
                        + ":\\s*(.*?)\\s*$");

        Matcher matcher = pattern.matcher(frontmatter);

        if (!matcher.find()) {
            return "";
        }

        String value = matcher.group(1).trim();

        if (value.length() >= 2) {
            if ((value.startsWith("\"")
                    && value.endsWith("\""))
                    || (value.startsWith("'")
                            && value.endsWith("'"))) {

                value = value.substring(
                        1, value.length() - 1);
            }
        }

        return value;
    }

    private static String extractSkillBody(
            String content) {

        String normalized = content.replace("\r\n", "\n");

        if (!normalized.startsWith("---\n")) {
            return normalized;
        }

        int closing = normalized.indexOf("\n---", 4);

        if (closing == -1) {
            throw new IllegalArgumentException(
                    "Missing closing frontmatter delimiter");
        }

        int bodyStart = closing + 4;

        if (bodyStart < normalized.length()
                && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }

        return normalized.substring(bodyStart);
    }
}
