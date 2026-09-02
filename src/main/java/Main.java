import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public class Main {
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
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .type(JsonValue.from("function"))
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "file_path", Map.of(
                                                "type", "string",
                                                "description", "The path to the file to read"
                                        )
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                                .build())
                        .build())
                .build();
                    ChatCompletionTool bashTool = ChatCompletionTool.builder()
                        .type(JsonValue.from("function"))
                        .function(FunctionDefinition.builder()
                            .name("Bash")
                            .description("Execute a shell command")
                            .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "command", Map.of(
                                        "type", "string",
                                        "description", "The command to execute"
                                    )
                                )))
                                .build())
                            .build())
                        .build();
                ChatCompletionTool writeTool = ChatCompletionTool.builder()
                    .type(JsonValue.from("function"))
                    .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "file_path", Map.of(
                                    "type", "string",
                                    "description", "The path of the file to write to"
                                ),
                                "content", Map.of(
                                    "type", "string",
                                    "description", "The content of the file to write"
                                )
                            )))
                            .build())
                        .build())
                    .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(prompt).build()));
        ObjectMapper objectMapper = new ObjectMapper();

        while (true) {
            ChatCompletion response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model("anthropic/claude-haiku-4.5")
                            .messages(messages)
                            .addTool(readTool)
                            .addTool(writeTool)
                            .addTool(bashTool)
                            .build()
            );

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            messages.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));

            var toolCalls = message.toolCalls();
            if (toolCalls.isEmpty() || toolCalls.get().isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
            }

            for (var toolCall : toolCalls.get()) {
                JsonNode arguments = objectMapper.readTree(toolCall.function().arguments());
                String result;
                if ("Read".equals(toolCall.function().name())) {
                    String filePath = arguments.get("file_path").asText();
                    result = Files.readString(Path.of(filePath));
                } else if ("Write".equals(toolCall.function().name())) {
                    String filePath = arguments.get("file_path").asText();
                    String content = arguments.get("content").asText();
                    Path path = Path.of(filePath);
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    Files.writeString(path, content);
                    result = "File written successfully";
                } else if ("Bash".equals(toolCall.function().name())) {
                    Process process = new ProcessBuilder("/bin/bash", "-c", arguments.get("command").asText())
                            .directory(Path.of(".").toFile())
                            .redirectErrorStream(true)
                            .start();
                    result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        result = "Command failed with exit code " + exitCode + "\n" + result;
                    }
                } else {
                    throw new RuntimeException("unsupported tool: " + toolCall.function().name());
                }

                messages.add(ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                        .toolCallId(toolCall.id())
                        .content(result)
                        .build()));
            }
        }
    }
}
