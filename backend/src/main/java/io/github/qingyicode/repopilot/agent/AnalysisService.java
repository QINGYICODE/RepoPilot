package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.dependency.DependencyService;
import io.github.qingyicode.repopilot.incident.IncidentService;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private final AnalysisScope scope;
    private final AnalysisRunStore store;
    private final AgentOrchestrator agent;
    private final ObjectProvider<ChatModel> models;
    private final IndexStore indices;
    private final DependencyService dependencies;
    private final IncidentService incidents;
    private final MeterRegistry metrics;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("analysis-run-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public AnalysisService(AnalysisScope scope,AnalysisRunStore store,AgentOrchestrator agent,ObjectProvider<ChatModel> models,
                           IndexStore indices,DependencyService dependencies,IncidentService incidents,MeterRegistry metrics) {
        this.scope=scope;this.store=store;this.agent=agent;this.models=models;this.indices=indices;this.dependencies=dependencies;this.incidents=incidents;this.metrics=metrics;
    }
    @PostConstruct void recover() {store.recover();}
    public AnalysisData.Result submit(AnalysisData.Request request) {
        var bound=scope.bind(request);
        var tools=new AnalysisTools(bound,indices,dependencies,incidents);
        String id=UUID.randomUUID().toString();
        // Authorization, scope refusal and missing-model checks finish without any model call.
        var initial=agent.new State(id,bound,tools,ignored->{}).result("RUNNING","QUEUED");
        store.create(bound,initial);
        String refusal=AnalysisScope.refusal(bound);
        if(refusal!=null || models.getIfAvailable()==null) {
            var result=agent.run(id,bound,tools,null,store::save);store.save(result);return result;
        }
        try {workers.submit(()-> {
            AnalysisData.Result result;
            try {result=agent.run(id,bound,tools,models.getObject(),store::save);}
            catch(RuntimeException error) {result=agent.new State(id,bound,tools,ignored->{}).result("REFUSED","ANALYSIS_FAILED");}
            store.save(result);
            metrics.counter("repopilot.analysis.runs","status",result.status(),"reason",result.reasonCode()).increment();
            for(var event:result.events()) metrics.counter("repopilot.analysis.tools","status",event.status(),"cached",Boolean.toString(event.cached())).increment();
        });} catch(RejectedExecutionException full) {
            store.save(agent.new State(id,bound,tools,ignored->{}).result("UNAVAILABLE","QUEUE_FULL"));
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,"ANALYSIS_QUEUE_FULL");
        }
        return initial;
    }
    public AnalysisData.Result get(String id) {return store.get(id);}
    @PreDestroy void shutdown() {workers.shutdownNow();}
}
