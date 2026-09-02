import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionTool;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

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

        ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("anthropic/claude-haiku-4.5")
                        .addUserMessage(prompt)
                    .addTool(ChatCompletionTool.builder()
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
                        .build())
                        .build()
        );

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        var message = response.choices().get(0).message();
        var toolCalls = message.toolCalls();
        if (toolCalls.isPresent() && !toolCalls.get().isEmpty()) {
            var toolCall = toolCalls.get().get(0);
            if ("Read".equals(toolCall.function().name())) {
                JsonNode arguments = new ObjectMapper().readTree(toolCall.function().arguments());
                String filePath = arguments.get("file_path").asText();
                System.out.print(Files.readString(Path.of(filePath)));
                return;
            }
        }

        // You can use print statements as follows for debugging, they'll be visible when running tests.
        System.err.println("Logs from your program will appear here!");

        System.out.print(message.content().orElse(""));
    }
}
