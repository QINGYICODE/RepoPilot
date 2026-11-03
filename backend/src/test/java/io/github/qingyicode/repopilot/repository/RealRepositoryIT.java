package io.github.qingyicode.repopilot.repository;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named="repopilot.upstream-it",matches="true")
class RealRepositoryIT {
    static final String PIN="3858f9c630cf989bb6809a86edf47c2be78dc9f1";
    @Autowired SyncService sync;
    @Autowired RepositoryStore repos;
    @Autowired IndexStore index;
    @Autowired RepositorySettings settings;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void importsActualPublicRepositoryAndExportsReviewMaterial() throws Exception {
        var repo=sync.register("spring-petclinic","spring-petclinic-microservices");
        var job=sync.enqueue(repo.getId(),PIN);
        long deadline=System.nanoTime()+Duration.ofMinutes(12).toNanos();
        while(!List.of("READY","FAILED").contains(job.status())&&System.nanoTime()<deadline){Thread.sleep(250);job=repos.job(job.id());}
        assertThat(job.status()).withFailMessage("Real import: %s",job.errorCode()).isEqualTo("READY");
        var snapshot=repos.snapshot(repo.getId(),job.snapshotId());
        assertThat(snapshot.coverage().get("historyComplete")).isEqualTo(true);
        assertThat(index.file(repo.getId(),snapshot.id(),"pom.xml",1,40).content()).contains("spring-petclinic-customers-service");
        assertThat(index.search(repo.getId(),snapshot.id(),"zipkin",null,null,null,null,null,5,null).items()).isNotEmpty();
        var tracing=index.search(repo.getId(),snapshot.id(),"spring-boot-starter-zipkin","DIFF",null,null,
                "9a76b4e34cd75f3d6bfa6f15775bf996c59e8989",null,20,null);
        assertThat(tracing.items()).isNotEmpty();
        assertThat(tracing.items()).allSatisfy(e->{assertThat(e.excerpt()).contains("spring-boot-starter-zipkin");
            assertThat(e.locator()).containsKeys("oldStartLine","newStartLine");});
        var report=new LinkedHashMap<String,Object>();
        report.put("repositoryId",repo.getId());report.put("snapshot",snapshot);
        var candidates=new ArrayList<Object>();
        try(var repository=new FileRepositoryBuilder().setGitDir(settings.storageDirectory().resolve(repo.getId()+".git").toFile()).build();
            var git=new Git(repository);var walk=new RevWalk(repository);var output=new java.io.ByteArrayOutputStream();var formatter=new DiffFormatter(output)) {
            formatter.setRepository(repository);
            long actualEpoch=walk.parseCommit(ObjectId.fromString(PIN)).getCommitTime();
            assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(committed_at) FROM git_commit WHERE repo_id=? AND sha=?",Long.class,repo.getId(),PIN)).isEqualTo(actualEpoch);
            for(var commit:git.log().add(ObjectId.fromString(PIN)).call()) {
                if(commit.getParentCount()!=1)continue;
                var parent=walk.parseCommit(commit.getParent(0));
                var poms=formatter.scan(parent.getTree(),commit.getTree()).stream().filter(d->d.getNewPath().endsWith("pom.xml")).toList();
                if(poms.isEmpty())continue;
                output.reset();for(var entry:poms)formatter.format(entry);
                String patch=output.toString(java.nio.charset.StandardCharsets.UTF_8);
                var item=new LinkedHashMap<String,Object>();
                item.put("sha",commit.name());item.put("parent",parent.name());item.put("subject",commit.getShortMessage());
                item.put("patch",patch.length()>16000?patch.substring(0,16000):patch);
                candidates.add(item);if(candidates.size()>=35)break;
            }
        }
        report.put("pomChangeCandidates",candidates);
        Files.writeString(java.nio.file.Path.of("target/upstream-review.json"),JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
