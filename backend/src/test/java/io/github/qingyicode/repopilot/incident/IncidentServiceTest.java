package io.github.qingyicode.repopilot.incident;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import io.github.qingyicode.repopilot.repository.*;
import io.github.qingyicode.repopilot.repository.RepositoryData.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IncidentServiceTest {
    final RepositoryStore repos=mock(RepositoryStore.class);
    final IndexStore index=mock(IndexStore.class);
    final GitMirror git=mock(GitMirror.class);
    final IncidentService service=new IncidentService(repos,index,git);
    final String sha="a".repeat(40), deployed="b".repeat(40), knowledge="c".repeat(40);
    String subject="fix: prevent ALERT_PIPELINE";
    String patch="@@ -1 +1 @@\n-throw ALERT_PIPELINE;\n+retry();";
    @BeforeEach void setup() {
        when(repos.snapshot(1,2)).thenReturn(new Snapshot(2,1,knowledge,"test","READY",Map.of("historyComplete",true),Map.of()));
        when(repos.repository(1)).thenReturn(new RepositoryRecord());
        when(index.search(eq(1L),eq(2L),anyString(),anyString(),isNull(),isNull(),nullable(String.class),isNull(),anyInt(),isNull()))
                .thenAnswer(call->{
                    String type=call.getArgument(3);
                    var e=new Evidence(type.equals("COMMIT")?"d".repeat(64):"e".repeat(64),1,type,sha,
                            type.equals("DIFF")?deployed:null,"api/Demo.java","","api",1,2,Map.of(),"hash",
                            type.equals("COMMIT")?subject:patch,"https://github.com/fixture/repo/commit/"+sha,false);
                    return new SearchPage(List.of(e),null,false,Map.of(),"EXACT");
                });
    }
    @Test void requiresActualChangedLinesAndNeverConfirmsAnIncidentLink() {
        var result=service.search(1,2,null,"ALERT_PIPELINE",null);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.candidates()).singleElement().satisfies(c->{
            assertThat(c.classification()).isEqualTo("RELATED_CANDIDATE");
            assertThat(c.applicability()).isEqualTo("DEPLOYED_VERSION_UNKNOWN");
            assertThat(c.evidence()).hasSize(2);
        });
        patch="@@ -1 +1 @@\n ALERT_PIPELINE\n-oldUnrelated();\n+newUnrelated();";
        assertThat(service.search(1,2,null,"ALERT_PIPELINE",null).reasonCode()).isEqualTo("NO_EVIDENCE");
    }
    @Test void rejectsTitleOnlyMatchesAndReverts() {
        patch="rename from old.java\nrename to new.java";
        assertThat(service.search(1,2,null,"ALERT_PIPELINE",null).candidates()).isEmpty();
        patch="-ALERT_PIPELINE\n+retry();"; subject="Revert fix: ALERT_PIPELINE";
        assertThat(service.search(1,2,null,"ALERT_PIPELINE",null).candidates()).isEmpty();
    }
    @Test void checksKnowledgeAndDeploymentAncestrySeparately() {
        when(git.isAncestor(any(),eq(deployed),eq(knowledge))).thenReturn(true);
        when(git.isAncestor(any(),eq(sha),eq(deployed))).thenReturn(true);
        assertThat(service.search(1,2,null,"ALERT_PIPELINE",deployed).candidates().getFirst().applicability())
                .isEqualTo("COMMIT_PRESENT_IN_DEPLOYED_ANCESTRY");
        when(git.isAncestor(any(),eq(deployed),eq(knowledge))).thenReturn(false);
        assertThatThrownBy(()->service.search(1,2,null,"ALERT_PIPELINE",deployed)).hasMessage("DEPLOYED_SHA_OUTSIDE_KNOWLEDGE");
    }
    @Test void removesSecretsAndBoundsSignatures() {
        assertThat(IncidentService.signatures("error sk-abcdefghijklmnopqrstuvwxyz123456 ALERT_PIPELINE java.net.ConnectException"))
                .contains("ALERT_PIPELINE","java.net.ConnectException").noneMatch(t->t.contains("sk-")||t.contains("REDACTED"));
        assertThat(io.github.qingyicode.repopilot.indexing.Redactor.cleanLog("password=supersecret Authorization: Bearer abcdef api_key=testsecret"))
                .doesNotContain("supersecret","abcdef","testsecret");
        assertThat(service.search(1,2,null,"纯中文且没有可检索符号",null).reasonCode()).isEqualTo("NO_EVIDENCE");
    }
}
