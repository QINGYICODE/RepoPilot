package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.system.CapabilityService;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** D1 connectivity probe only. The production Agent state machine belongs to D6/D7. */
@Service
@Profile("llm")
public class CapabilityProbeService {
    private final ChatModel model;
    private final CapabilityService capabilities;
    private final JsonMapper json = JsonMapper.builder().build();

    public CapabilityProbeService(ChatModel model, CapabilityService capabilities) {
        this.model = model;
        this.capabilities = capabilities;
    }

    public ProbeResult verify() {
        var tools = new CapabilityTools(capabilities);
        if (!(model.getDefaultOptions() instanceof OpenAiChatOptions defaults)) {
            throw new IllegalStateException("OPENAI_OPTIONS_REQUIRED");
        }
        var options = defaults.mutate().toolCallbacks(ToolCallbacks.from(tools)).build();
        var prompt = new Prompt(List.of(
                new SystemMessage("You verify RepoPilot's actual capabilities. First call getProjectCapabilities exactly once, with no arguments. "
                        + "After the result, return ONLY a JSON object with phase, repositoryCount, and indexed=false. "
                        + "Use the tool's phase and repositoryCount exactly. No markdown, guesses or additional tool calls."),
                new UserMessage("Read the current project capabilities using the tool and report its actual phase and repository count.")), options);

        ChatResponse first = model.call(prompt);
        if (first == null || !first.hasToolCalls()) {
            throw new IllegalStateException("MODEL_DID_NOT_REQUEST_TOOL");
        }
        List<AssistantMessage.ToolCall> calls = first.getResult().getOutput().getToolCalls();
        if (calls.size() != 1 || !"getProjectCapabilities".equals(calls.getFirst().name())
                || calls.getFirst().id() == null || calls.getFirst().id().isBlank()) {
            throw new IllegalStateException("TOOL_CALL_LIMIT_OR_NAME_INVALID");
        }
        var arguments = json.readTree(calls.getFirst().arguments());
        if (!arguments.isObject() || !arguments.isEmpty()) {
            throw new IllegalStateException("TOOL_ARGUMENTS_INVALID");
        }
        var execution = ToolCallingManager.builder().build().executeToolCalls(prompt, first);
        ChatResponse second = model.call(new Prompt(execution.conversationHistory(), options));
        if (second == null || second.hasToolCalls() || second.getResult() == null) {
            throw new IllegalStateException("MODEL_CALL_LIMIT_REACHED");
        }
        String answer = second.getResult().getOutput().getText();
        if (answer == null || answer.length() > 4096) {
            throw new IllegalStateException("MODEL_OUTPUT_INVALID");
        }
        var output = json.readTree(answer);
        var actual = tools.lastSnapshot();
        if (!output.isObject() || !actual.phase().equals(output.path("phase").asString())
                || !output.path("repositoryCount").isIntegralNumber()
                || output.path("repositoryCount").asLong() != actual.repositoryCount()
                || !output.path("indexed").isBoolean() || output.path("indexed").asBoolean()) {
            throw new IllegalStateException("MODEL_OUTPUT_NOT_SUPPORTED_BY_TOOL");
        }
        return new ProbeResult(2, tools.invocations(), actual.phase(), actual.repositoryCount());
    }

    public record ProbeResult(int modelCalls, int toolCalls, String phase, long repositoryCount) {
    }
}
