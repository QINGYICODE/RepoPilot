package io.github.qingyicode.repopilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.qingyicode.repopilot.system.CapabilityService;
import io.github.qingyicode.repopilot.system.CapabilitySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class CapabilityProbeServiceTest {
    private final ChatModel model = mock(ChatModel.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final CapabilityProbeService probe = new CapabilityProbeService(model, capabilities);

    @BeforeEach
    void setup() {
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("configured-test-model").maxRetries(0).maxTokens(512).build());
        when(capabilities.snapshot()).thenReturn(new CapabilitySnapshot(
                "RepoPilot", "D1", Map.of(), 3, true, List.of(), List.of("REPOSITORY_SYNC")));
    }

    @Test
    void executesOneReadOnlyToolAndPreservesToolResultInSecondPrompt() {
        when(model.call(any(Prompt.class))).thenReturn(toolRequest("getProjectCapabilities", "{}"),
                answer("{\"phase\":\"D1\",\"repositoryCount\":3,\"indexed\":false}"));

        assertThat(probe.verify()).isEqualTo(new CapabilityProbeService.ProbeResult(2, 1, "D1", 3));
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.times(2)).call(prompts.capture());
        for (var sent : prompts.getAllValues()) {
            var options = (OpenAiChatOptions) sent.getOptions();
            assertThat(options.getModel()).isEqualTo("configured-test-model");
            assertThat(options.getMaxRetries()).isZero();
            assertThat(options.getMaxTokens()).isEqualTo(512);
        }
        assertThat(prompts.getAllValues().get(1).getInstructions())
                .anySatisfy(message -> assertThat(message).isInstanceOf(ToolResponseMessage.class));
    }

    @Test
    void doesNotAcceptAProviderAnswerWithoutTheRequiredTool() {
        when(model.call(any(Prompt.class))).thenReturn(answer("I already know the answer"));
        assertThatThrownBy(probe::verify).hasMessage("MODEL_DID_NOT_REQUEST_TOOL");
        verify(capabilities, never()).snapshot();
    }

    @Test
    void rejectsParallelToolBatchBeforeAnyInvocation() {
        var message = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("a", "function", "getProjectCapabilities", "{}"),
                new AssistantMessage.ToolCall("b", "function", "getProjectCapabilities", "{}"))).build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(message))));
        assertThatThrownBy(probe::verify).hasMessage("TOOL_CALL_LIMIT_OR_NAME_INVALID");
        verify(capabilities, never()).snapshot();
    }

    @Test
    void rejectsUnknownToolsBeforeExecution() {
        when(model.call(any(Prompt.class))).thenReturn(toolRequest("executeShell", "{}"));
        assertThatThrownBy(probe::verify).hasMessage("TOOL_CALL_LIMIT_OR_NAME_INVALID");
        verify(capabilities, never()).snapshot();
    }

    @Test
    void rejectsInjectedArgumentsBeforeExecution() {
        when(model.call(any(Prompt.class))).thenReturn(toolRequest("getProjectCapabilities", "{\"repoId\":99}"));
        assertThatThrownBy(probe::verify).hasMessage("TOOL_ARGUMENTS_INVALID");
        verify(capabilities, never()).snapshot();
    }

    @Test
    void refusesAClaimThatDisagreesWithToolEvidence() {
        when(model.call(any(Prompt.class))).thenReturn(toolRequest("getProjectCapabilities", "{}"),
                answer("{\"phase\":\"D1\",\"repositoryCount\":999,\"indexed\":false}"));
        assertThatThrownBy(probe::verify).hasMessage("MODEL_OUTPUT_NOT_SUPPORTED_BY_TOOL");
    }

    @Test
    void stopsBeforeExecutingASecondToolRound() {
        when(model.call(any(Prompt.class))).thenReturn(toolRequest("getProjectCapabilities", "{}"));
        assertThatThrownBy(probe::verify).hasMessage("MODEL_CALL_LIMIT_REACHED");
        verify(capabilities, org.mockito.Mockito.times(1)).snapshot();
        verify(model, org.mockito.Mockito.times(2)).call(any(Prompt.class));
    }

    private static ChatResponse toolRequest(String name, String arguments) {
        var message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("probe-1", "function", name, arguments))).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
