package io.github.qingyicode.repopilot.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;

class AgentContextTest {
    final AnalysisData.Bound bound=new AnalysisData.Bound(new AnalysisData.Request(1L,2L,AnalysisData.Task.INCIDENT_FIX,"告警 ALERT_PIPELINE",null,null,null),"fixture/repo","a".repeat(40),null);
    @Test void dropsOnlyWholeExchangesAndPreservesRulesQuestionScopeAndCounters() {
        var context=new AgentContext("RULES_DO_NOT_REMOVE",bound);
        for(int i=0;i<5;i++) context.append(pair("call"+i,"payload".repeat(500)));
        var messages=context.build(Map.of("requestedTools",5),new ToolCallback[0],12000);
        assertThat(messages).hasSize(7);
        assertThat(messages.getFirst().getText()).contains("RULES_DO_NOT_REMOVE");
        assertThat(messages.get(1).getText()).contains("fixture/repo","ALERT_PIPELINE",bound.knowledgeSha());
        assertThat(messages.get(2).getText()).contains("requestedTools", "5");
        assertThat(context.removed).isEqualTo(3);
        assertThat(((AssistantMessage)messages.get(3)).getToolCalls().getFirst().id()).isEqualTo("call3");
        assertThat(((ToolResponseMessage)messages.get(4)).getResponses().getFirst().id()).isEqualTo("call3");
    }
    @Test void countsToolPayloadBytesAndRemovesOversizedPairsRatherThanOrphaningResponses() {
        var context=new AgentContext("RULES",bound);
        context.append(pair("huge","证据".repeat(6000)));
        var messages=context.build(Map.of(),new ToolCallback[0],24000);
        assertThat(messages).hasSize(3);
        assertThat(context.removed).isEqualTo(1);
        assertThat(context.size(messages,new ToolCallback[0])).isLessThan(24000);
    }
    @Test void rejectsAnOrphanedOrMismatchedToolResult() {
        var context=new AgentContext("RULES",bound);
        assertThatThrownBy(()->context.append(List.of(new UserMessage("orphan")))).hasMessage("INCOMPLETE_TOOL_EXCHANGE");
        var pair=pair("one","data");
        assertThatThrownBy(()->context.append(List.of(pair.getFirst(),pair("two","data").getLast()))).hasMessage("UNPAIRED_TOOL_EXCHANGE");
    }
    private static List<Message> pair(String id,String data) {
        return List.of(AssistantMessage.builder().content("private scratch text is not retained").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","searchRepository","{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id,"searchRepository",data))).build());
    }
}
