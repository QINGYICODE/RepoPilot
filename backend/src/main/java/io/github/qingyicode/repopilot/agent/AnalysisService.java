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
    private final ConcurrentMap<String,FutureTask<Void>> inFlight=new ConcurrentHashMap<>();
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
            var result=agent.run(id,bound,tools,null,store::save);if(store.save(result)) record(result);return result;
        }
        var task=new FutureTask<Void>(()-> {
            AnalysisData.Result result;
            try {result=agent.run(id,bound,tools,models.getObject(),store::save);}
            catch(RuntimeException error) {result=agent.new State(id,bound,tools,ignored->{}).result("REFUSED","ANALYSIS_FAILED");}
            if(store.save(result)) record(result);
            return null;
        }) {@Override protected void done() {inFlight.remove(id,this);}};
        inFlight.put(id,task);
        try {workers.execute(task);} catch(RejectedExecutionException full) {
            inFlight.remove(id,task);
            store.save(agent.new State(id,bound,tools,ignored->{}).result("UNAVAILABLE","QUEUE_FULL"));
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,"ANALYSIS_QUEUE_FULL");
        }
        return initial;
    }
    public AnalysisData.Result get(String id) {return store.get(id);}
    public AnalysisData.Result cancel(String id) {
        var current=store.get(id);
        if(!current.status().equals("RUNNING")) return current;
        var cancelled=new AnalysisData.Result(current.id(),"CANCELLED","CANCELLED","分析已取消，后续结果不会覆盖此状态。",current.repository(),current.knowledgeSha(),
                current.baseSha(),java.util.List.of(),null,null,current.events(),current.usage(),current.model(),current.promptVersion(),current.warnings());
        if(store.save(cancelled)) {
            var task=inFlight.get(id);if(task!=null) task.cancel(true);
            record(cancelled);
        } else if(store.get(id).status().equals("RUNNING")) throw DomainException.conflict("RUN_OWNED_BY_ANOTHER_PROCESS");
        return store.get(id);
    }
    private void record(AnalysisData.Result result) {
        metrics.counter("repopilot.analysis.runs","status",result.status(),"reason",result.reasonCode()).increment();
        for(var event:result.events()) metrics.counter("repopilot.analysis.tools","status",event.status(),"cached",Boolean.toString(event.cached())).increment();
    }
    @PreDestroy void shutdown() {store.interruptOwned();workers.shutdownNow();}
}
