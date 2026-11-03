package io.github.qingyicode.repopilot.agent;

import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

/** Retains immutable task/rules and complete tool exchanges; never summarizes untrusted text as instructions. */
final class AgentContext {
    private final JsonMapper json=JsonMapper.builder().build();
    private final List<Message> roots;
    private final Deque<List<Message>> exchanges=new ArrayDeque<>();
    int removed;
    AgentContext(String rules,AnalysisData.Bound bound) {
        roots=List.of(new SystemMessage(rules),new UserMessage(json.writeValueAsString(bound)));
    }
    List<Message> build(Map<String,Object> trustedState,ToolCallback[] tools,int maxBytes) {
        var summary=new SystemMessage("Server-verified counters and references only: "+json.writeValueAsString(trustedState));
        while(true) {
            var messages=new ArrayList<>(roots);messages.add(summary);exchanges.forEach(messages::addAll);
            if(size(messages,tools)<=maxBytes || exchanges.isEmpty()) return messages;
            exchanges.removeFirst();removed++;
        }
    }
    void append(List<Message> history) {
        int last=history.size()-1;
        if(last<1 || !(history.get(last) instanceof ToolResponseMessage response) || !(history.get(last-1) instanceof AssistantMessage assistant))
            throw new IllegalStateException("INCOMPLETE_TOOL_EXCHANGE");
        Set<String> calls=new HashSet<>(),responses=new HashSet<>();
        assistant.getToolCalls().forEach(c->calls.add(c.id()));response.getResponses().forEach(r->responses.add(r.id()));
        if(!calls.equals(responses)) throw new IllegalStateException("UNPAIRED_TOOL_EXCHANGE");
        exchanges.addLast(List.of(AssistantMessage.builder().content("").toolCalls(assistant.getToolCalls()).build(),response));
        while(exchanges.size()>2) {exchanges.removeFirst();removed++;}
    }
    int size(List<Message> messages,ToolCallback[] tools) {
        int total=1024;
        for(var m:messages) {
            String text=m instanceof ToolResponseMessage tool?json.writeValueAsString(tool.getResponses()):
                    m instanceof AssistantMessage assistant?json.writeValueAsString(assistant.getToolCalls())+Objects.toString(m.getText(),""):Objects.toString(m.getText(),"");
            total+=AgentOrchestrator.bytes(text)+128;
        }
        for(var tool:tools) total+=AgentOrchestrator.bytes(tool.getToolDefinition().inputSchema())+AgentOrchestrator.bytes(tool.getToolDefinition().description())+128;
        return total;
    }
}
