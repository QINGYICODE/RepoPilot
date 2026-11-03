package io.github.qingyicode.repopilot.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import io.github.qingyicode.repopilot.dependency.DependencyData;
import io.github.qingyicode.repopilot.dependency.DependencyService;
import io.github.qingyicode.repopilot.indexing.IndexProduct;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named="repopilot.mysql-it",matches="true")
@Import(RepositoryPipelineIT.Configuration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryPipelineIT {
    static final Path ROOT = temp();
    static final Path REMOTE = ROOT.resolve("remote");
    static String base; static String head; static String removed; static String side;
    @Autowired SyncService sync;
    @Autowired RepositoryStore repos;
    @Autowired IndexStore indices;
    @Autowired DependencyService dependencies;
    @Autowired io.github.qingyicode.repopilot.incident.IncidentService incidents;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    long repoId;
    long baseId;
    long headId;

    static Path temp() { try{return Files.createTempDirectory("repopilot-pipeline-");}catch(Exception e){throw new IllegalStateException(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("repopilot.allowed-repositories",()->"fixture/repo,fixture/other");
        properties.add("repopilot.storage-directory",()->ROOT.resolve("mirrors").toString());
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary GitHubGateway fixtures() { return (owner,name)->new RepositoryData.Metadata(name.equals("repo")?700:701,owner+"/"+name,"main",false); }
        @Bean @Primary GitMirror fixtureMirror(RepositorySettings settings) {
            return new GitMirror(settings) {
                @Override public <T> T withSnapshot(RepositoryRecord record,String sha,Function<Repository,T> action) {
                    return withRemote(record,sha,REMOTE.toUri().toString(),action);
                }
            };
        }
    }
    @BeforeAll void createFixtureAndImportSnapshots() throws Exception {
        Files.createDirectories(REMOTE);
        try(var git=Git.init().setDirectory(REMOTE.toFile()).setInitialBranch("main").call()) {
            write("pom.xml",root("1"));
            write("lib/pom.xml",child("lib","<dependencies><dependency><groupId>external</groupId><artifactId>core</artifactId><version>${library.version}</version></dependency></dependencies>"));
            write("api/pom.xml",child("api","<dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>"));
            write("api/src/main/resources/application.yml","spring:\n  application:\n    name: api\n");
            write("lib/src/main/java/example/Demo.java","package example;\nclass Demo {\n @Deprecated\n void fail() { throw new IllegalStateException(\"ALERT_PIPELINE 连接失败\"); }\n}\n");
            Path outside=ROOT.resolve("outside.txt"); Files.writeString(outside,"DO_NOT_INDEX_OUTSIDE");
            Files.createSymbolicLink(REMOTE.resolve("escape.java"),outside);
            base=commit(git,"Initial fixture with ALERT_PIPELINE");
            write("pom.xml",root("2"));
            Files.move(REMOTE.resolve("lib/src/main/java/example/Demo.java"),REMOTE.resolve("lib/src/main/java/example/Renamed.java"));
            write("lib/src/main/java/example/Renamed.java","package example;\nclass Demo {\n @Deprecated\n void fail() { throw new IllegalStateException(\"ALERT_PIPELINE retry enabled 连接失败\"); }\n}\n");
            head=commit(git,"fix: ALERT_PIPELINE dependency update");
            write("lib/pom.xml",child("lib","")); removed=commit(git,"Remove obsolete external core");
            git.checkout().setCreateBranch(true).setName("side").setStartPoint(base).call();
            write("side.md","side branch only"); side=commit(git,"Unreachable from allowed main");
            git.checkout().setName("main").call();
        }
        var registered=sync.register("fixture","repo"); repoId=registered.getId();
        assertThat(sync.register("fixture","repo").getId()).isEqualTo(repoId);
        baseId=ready(base).snapshotId(); headId=ready(head).snapshotId();
    }
    @Test void fixedTreeEvidenceHistoryAndIncrementalReuseArePublished() {
        var snapshot=repos.snapshot(repoId,headId);
        assertThat(snapshot.commitSha()).isEqualTo(head);
        assertThat(snapshot.coverage().get("historyComplete")).isEqualTo(true);
        assertThat(snapshot.coverage().get("warnings").toString()).contains("SYMLINK_NOT_INDEXED");
        assertThat(((Number)snapshot.stats().get("reusedBlobCount")).intValue()).isPositive();
        assertThat(indices.files(repoId,headId)).noneMatch(f->f.path().equals("escape.java"));
        assertThat(indices.file(repoId,baseId,"pom.xml",1,20).content()).contains("<library.version>1</library.version>");
        assertThat(indices.file(repoId,headId,"pom.xml",1,20).content()).contains("<library.version>2</library.version>");
        var symbols=indices.search(repoId,headId,"Demo#fail", "FILE",null,"example.Demo#fail()",null,null,20,null);
        assertThat(symbols.items()).isNotEmpty();
        assertThat(symbols.items()).allSatisfy(e->{
            assertThat(indices.evidence(repoId,headId,e.id()).contentHash()).isEqualTo(e.contentHash());
            assertThat(e.sourceSha()).isEqualTo(head); assertThat(e.excerpt()).contains("ALERT_PIPELINE");
        });
        assertThat(indices.files(repoId,headId)).noneMatch(f->f.path().endsWith("Demo.java"));
        var diffs=indices.search(repoId,headId,"library.version","DIFF",null,null,head,null,20,null);
        assertThat(diffs.items()).isNotEmpty();
        assertThat(diffs.items()).allSatisfy(e->{assertThat(e.parentSha()).isEqualTo(base);assertThat(e.locator()).containsKeys("oldPath","oldStartLine","newStartLine");});
        assertThat(indices.search(repoId,headId,"连接失败",null,null,null,null,null,20,null).items()).isNotEmpty();
        var impact=dependencies.compare(repoId,baseId,headId,null,8);
        assertThat(impact.affectedServices()).containsExactly("api");
        assertThat(impact.impacts()).allSatisfy(i->i.evidenceIds().forEach(id->assertThat(indices.evidence(repoId,i.view().equals("BASE")?baseId:headId,id)).isNotNull()));
    }
    @Test void paginationAndSnapshotIsolationAreEnforced() {
        var first=indices.search(repoId,headId,"fixture",null,null,null,null,null,1,null);
        assertThat(first.nextCursor()).isNotBlank();
        var next=indices.search(repoId,headId,"fixture",null,null,null,null,null,1,first.nextCursor());
        assertThat(next.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
        assertThatThrownBy(()->indices.search(repoId,headId,"different",null,null,null,null,null,1,first.nextCursor())).hasMessage("INVALID_SEARCH_CURSOR");
        var current=indices.search(repoId,headId,"library.version","FILE",null,null,null,null,1,null).items().getFirst();
        assertThatThrownBy(()->indices.evidence(repoId,baseId,current.id())).hasMessage("EVIDENCE_NOT_IN_SNAPSHOT");
        long other=sync.register("fixture","other").getId();
        assertThatThrownBy(()->indices.evidence(other,headId,current.id())).hasMessage("SNAPSHOT_NOT_READY");
        assertThatThrownBy(()->indices.file(repoId,headId,"../secret",1,2)).hasMessage("INVALID_REPOSITORY_PATH");
    }
    @Test void incidentCandidatesUseRealPatchAndJgitDeploymentAncestry() {
        var result=incidents.search(repoId,headId,"lib","ALERT_PIPELINE",base);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.candidates()).singleElement().satisfies(c->{
            assertThat(c.commitSha()).isEqualTo(head);
            assertThat(c.classification()).isEqualTo("RELATED_CANDIDATE");
            assertThat(c.applicability()).isEqualTo("COMMIT_NOT_IN_DEPLOYED_ANCESTRY");
            c.evidence().forEach(e->assertThat(indices.evidence(repoId,headId,e.id())).isEqualTo(e));
        });
        assertThat(incidents.search(repoId,headId,"lib","ALERT_PIPELINE",head).candidates().getFirst().applicability())
                .isEqualTo("COMMIT_PRESENT_IN_DEPLOYED_ANCESTRY");
        assertThat(incidents.search(repoId,headId,"lib","NO_SUCH_SIGNATURE_987",base).reasonCode()).isEqualTo("NO_EVIDENCE");
        assertThatThrownBy(()->incidents.search(repoId,headId,"other","ALERT_PIPELINE",base)).hasMessage("UNKNOWN_SERVICE_MODULE");
    }
    @Test void failuresAndRetriesNeverReplaceTheActiveSnapshot() throws Exception {
        Long active=repos.repository(repoId).getActiveSnapshotId();
        var unknown=sync.enqueue(repoId,"f".repeat(40)); var failed=terminal(unknown.id());
        assertThat(failed.errorCode()).isEqualTo("UNKNOWN_COMMIT");
        var retry=sync.enqueue(repoId,"f".repeat(40)); assertThat(retry.id()).isEqualTo(unknown.id());
        assertThat(terminal(retry.id()).attempts()).isEqualTo(2);
        assertThat(repos.repository(repoId).getActiveSnapshotId()).isEqualTo(active);
        assertThat(terminal(sync.enqueue(repoId,side).id()).errorCode()).isEqualTo("UNKNOWN_COMMIT");
        assertThat(sync.enqueue(repoId,head).snapshotId()).isEqualTo(headId);
        assertThatThrownBy(()->sync.register("evil.example","outside")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->sync.enqueue(repoId,"main")).hasMessage("FULL_COMMIT_SHA_REQUIRED");
    }
    @Test void publishingRollsBackIfAnyIndexRowCannotBeWritten() {
        Long active=repos.repository(repoId).getActiveSnapshotId();
        var pending=repos.prepare(repoId,"a".repeat(40));
        var bad=new IndexProduct(List.of(),List.of(new RepositoryData.IndexedFile("bad.java","b".repeat(40),"0".repeat(64),".","java",1)),
                List.of(),List.of(),new DependencyData.Graph(List.of(),List.of(),List.of()),Map.of(),Map.of());
        assertThatThrownBy(()->indices.publish(pending,"nonexistent",bad)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repos.repository(repoId).getActiveSnapshotId()).isEqualTo(active);
        assertThatThrownBy(()->repos.snapshot(repoId,pending.id())).hasMessage("SNAPSHOT_NOT_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_file WHERE snapshot_id=?",Integer.class,pending.id())).isZero();
    }
    @Test void expiredLeaseCanResumeWithoutReplacingReadyDataEarly() throws Exception {
        var job=repos.enqueue(repoId,removed);
        jdbc.update("UPDATE sync_job SET status='FETCHING',attempts=1,lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",job.id());
        Long active=repos.repository(repoId).getActiveSnapshotId();repos.recover();
        assertThat(repos.job(job.id()).status()).isEqualTo("QUEUED");
        assertThat(repos.repository(repoId).getActiveSnapshotId()).isEqualTo(active);
        sync.dispatch();var completed=terminal(job.id());
        assertThat(completed.status()).isEqualTo("READY");assertThat(completed.attempts()).isEqualTo(2);
    }
    @Test void httpEndpointsExposeTheBoundedPipeline() throws Exception {
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            String baseUrl="http://127.0.0.1:"+port+"/api";
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl+"/repositories")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("fixture","repo");
            var invalid=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl+"/repositories/"+repoId+"/sync-jobs"))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"commitSha\":\"main\"}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(invalid.statusCode()).isEqualTo(400);assertThat(invalid.body()).contains("FULL_COMMIT_SHA_REQUIRED");
            var compared=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl+"/repositories/"+repoId+"/dependency-impact"))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"baseSnapshotId\":"+baseId+",\"headSnapshotId\":"+headId+"}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(compared.statusCode()).isEqualTo(200);assertThat(compared.body()).contains("api","external:core","HEAD","BASE");
        }
    }
    @AfterAll void cleanup() { jdbc.update("DELETE FROM repository WHERE owner='fixture' AND name IN ('repo','other')"); }
    private RepositoryData.SyncJob ready(String sha) throws Exception {
        var job=terminal(sync.enqueue(repoId,sha).id()); assertThat(job.status()).withFailMessage("Sync failed: %s",job.errorCode()).isEqualTo("READY");return job;
    }
    private RepositoryData.SyncJob terminal(String id) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<deadline) {var job=repos.job(id);if(List.of("READY","FAILED").contains(job.status()))return job;Thread.sleep(50);}
        throw new AssertionError("Sync timed out");
    }
    private static void write(String path,String body) throws Exception {Path file=REMOTE.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,body);}
    private static String commit(Git git,String message) throws Exception {git.add().addFilepattern(".").call();git.add().setUpdate(true).addFilepattern(".").call();return git.commit().setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").setMessage(message).call().name();}
    private static String root(String version) {return "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><properties><library.version>"+version+"</library.version></properties><modules><module>lib</module><module>api</module></modules></project>";}
    private static String child(String name,String body) {return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>"+name+"</artifactId>"+body+"</project>";}
}
