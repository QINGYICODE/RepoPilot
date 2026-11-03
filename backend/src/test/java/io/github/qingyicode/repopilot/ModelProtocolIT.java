package io.github.qingyicode.repopilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.qingyicode.repopilot.agent.CapabilityProbeService;
import io.github.qingyicode.repopilot.repository.RepositoryMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Tests real Spring AI/OpenAI SDK wiring against a LOCAL protocol stub, never a real LLM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("llm")
@EnabledIfSystemProperty(named = "repopilot.mysql-it", matches = "true")
class ModelProtocolIT {
    private static final AtomicInteger requests = new AtomicInteger();
    private static final CopyOnWriteArrayList<String> requestedModels = new CopyOnWriteArrayList<>();
    private static HttpServer server;
    private static volatile long repositoryCount;
    private static volatile boolean toolResultReceived;

    @Autowired CapabilityProbeService probe;
    @Autowired RepositoryMapper mapper;

    @DynamicPropertySource
    static void localModel(DynamicPropertyRegistry properties) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var json = JsonMapper.builder().build();
            var request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestedModels.add(request.path("model").asString());
            int number = requests.incrementAndGet();
            String choice;
            if (number == 1) {
                choice = "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"local-probe\",\"type\":\"function\",\"function\":{\"name\":\"getProjectCapabilities\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}";
            } else {
                for (var message : request.path("messages")) {
                    if ("tool".equals(message.path("role").asString())) {
                        toolResultReceived = message.path("content").asString().contains("repositoryCount");
                    }
                }
                String content = json.writeValueAsString("{\"phase\":\"D7\",\"repositoryCount\":" + repositoryCount + "}");
                choice = "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + content + "},\"finish_reason\":\"stop\"}";
            }
            byte[] body = ("{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"local-protocol-stub\",\"choices\":[" + choice + "],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.add("spring.ai.openai.api-key", () -> "local-test-key-not-a-real-secret");
        properties.add("spring.ai.openai.chat.options.model", () -> "local-protocol-stub");
    }

    @Test
    void realSdkCompletesBoundedToolRoundTripAgainstLocalStub() {
        repositoryCount = mapper.selectCount(null);
        var result = probe.verify();
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(requestedModels).containsExactly("local-protocol-stub", "local-protocol-stub");
        assertThat(toolResultReceived).isTrue();
    }

    @AfterAll
    static void stopLocalStub() {
        if (server != null) server.stop(0);
    }
}
