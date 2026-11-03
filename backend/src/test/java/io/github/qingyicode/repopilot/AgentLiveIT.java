package io.github.qingyicode.repopilot;

import static org.assertj.core.api.Assertions.*;
import io.github.qingyicode.repopilot.agent.*;
import io.github.qingyicode.repopilot.incident.IncidentService;
import io.github.qingyicode.repopilot.repository.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in: uses prepared real repository snapshots and the configured real model. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("llm")
@EnabledIfSystemProperty(named="repopilot.agent-live",matches="true")
class AgentLiveIT {
    static final String PIN="3858f9c630cf989bb6809a86edf47c2be78dc9f1";
    @Autowired RepositoryStore repositories;
    @Autowired IncidentService incidents;
    @Autowired AnalysisService analyses;
    final JsonMapper json=JsonMapper.builder().build();

    @Test void fiveRealHistoricalPatchesRemainCandidatesRatherThanFabricatedIncidents() throws Exception {
        var repo=requiredRepository();var snapshot=snapshot(repo.getId(),PIN);
        var cases=json.readTree(getClass().getResourceAsStream("/real-fix-cases.json"));
        var reports=new ArrayList<Object>();
        for(var item:cases) {
            String signature=item.path("signature").asString(),sha=item.path("sha").asString();
            var result=incidents.search(repo.getId(),snapshot.id(),null,signature,null);
            assertThat(result.candidates()).withFailMessage("No real candidate for %s: %s",signature,result).anySatisfy(c->{
                assertThat(c.commitSha()).isEqualTo(sha);
                assertThat(c.classification()).isEqualTo("RELATED_CANDIDATE");
                assertThat(c.evidence()).anyMatch(e->e.type().equals("DIFF")&&e.excerpt().contains(signature));
            });
            reports.add(Map.of("inputKind","SIMULATED_SIGNATURE_FROM_REAL_PATCH","reviewBasis",item.path("basis").asString(),"result",result));
        }
        Files.writeString(Path.of("target/real-fix-results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
    }
    @Test void realModelCompletesBothBusinessTasksAndScopeRefusal() throws Exception {
        var repo=requiredRepository();
        var head=snapshot(repo.getId(),"597ad1fbfabadfef4ed8a1d475f7f34aa2666aab");
        var base=snapshot(repo.getId(),"17cad88d533554f53c634cfa78211ea2f51b0a46");
        var impact=await(analyses.submit(new AnalysisData.Request(repo.getId(),head.id(),AnalysisData.Task.DEPENDENCY_IMPACT,
                "spring-petclinic-admin-server 模块的依赖变更会影响哪些服务？请比较固定版本并给出证据。",base.id(),"spring-petclinic-admin-server",null)));
        assertThat(impact.status()).withFailMessage("Impact run: %s",impact).isIn("PARTIAL","ANSWERED");
        assertThat(impact.reasonCode()).isEqualTo("EVIDENCE_VERIFIED");
        assertThat(impact.dependency().affectedServices()).containsExactly("spring-petclinic-admin-server");
        var pin=snapshot(repo.getId(),PIN);
        var fix=await(analyses.submit(new AnalysisData.Request(repo.getId(),pin.id(),AnalysisData.Task.INCIDENT_FIX,
                "出现 zipkin tracing 告警，请查 spring-boot-starter-zipkin 的历史修复 commit；没有明确事故关联时只列候选。",null,"spring-petclinic-api-gateway",null)));
        assertThat(fix.status()).withFailMessage("Fix run: %s",fix).isEqualTo("PARTIAL");
        assertThat(fix.reasonCode()).isEqualTo("EVIDENCE_VERIFIED");
        assertThat(fix.incident().candidates()).anyMatch(c->c.commitSha().equals("9a76b4e34cd75f3d6bfa6f15775bf996c59e8989"));
        var refused=analyses.submit(new AnalysisData.Request(repo.getId(),pin.id(),AnalysisData.Task.INCIDENT_FIX,"今天天气怎么样？",null,null,null));
        assertThat(refused.status()).isEqualTo("REFUSED");assertThat(refused.usage().modelCalls()).isZero();
        Files.writeString(Path.of("target/agent-live-results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "promptVersion","repo-agent-v1","knowledgeSha",PIN,"scope","SMOKE_TEST_NOT_FAILURE_RATE_BENCHMARK","runs",List.of(impact,fix,refused))));
    }
    private RepositoryRecord requiredRepository() {
        var repo=repositories.find("spring-petclinic","spring-petclinic-microservices");
        assertThat(repo).withFailMessage("Import the documented real repository and snapshots before agent-check").isNotNull();
        return repo;
    }
    private RepositoryData.Snapshot snapshot(long repoId,String sha) {
        return repositories.snapshots(repoId).stream().filter(s->s.commitSha().equals(sha)).findFirst()
                .orElseThrow(()->new AssertionError("Required real snapshot missing: "+sha));
    }
    private AnalysisData.Result await(AnalysisData.Result result) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(100).toNanos();
        while(result.status().equals("RUNNING")&&System.nanoTime()<deadline) {Thread.sleep(100);result=analyses.get(result.id());}
        return result;
    }
}
