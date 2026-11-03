package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import io.github.qingyicode.repopilot.indexing.Redactor;
import io.github.qingyicode.repopilot.repository.RepositoryStore;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Local single-user authorization: repository allowlist + immutable task scope, not multi-tenant RBAC. */
@Component
public class AnalysisScope {
    private final RepositoryStore repositories;
    private final IndexStore indices;
    public AnalysisScope(RepositoryStore repositories,IndexStore indices) {this.repositories=repositories;this.indices=indices;}
    public AnalysisData.Bound bind(AnalysisData.Request input) {
        if(input.repoId()==null || input.repoId()<1 || input.snapshotId()==null || input.snapshotId()<1 || input.task()==null
                || input.question()==null || input.question().isBlank() || input.question().length()>8000)
            throw DomainException.invalid("INVALID_ANALYSIS_REQUEST");
        var repo=repositories.repository(input.repoId());
        var snapshot=repositories.snapshot(input.repoId(),input.snapshotId());
        String baseSha=null;
        if(input.baseSnapshotId()!=null) baseSha=repositories.snapshot(input.repoId(),input.baseSnapshotId()).commitSha();
        String module=input.module()==null || input.module().isBlank()?null:input.module();
        if(module!=null && !module.equals(".")) RepositoryPolicy.path(module);
        if(module!=null && indices.graph(input.repoId(),input.snapshotId()).modules().stream().noneMatch(m->m.path().equals(module))
                && (input.baseSnapshotId()==null || indices.graph(input.repoId(),input.baseSnapshotId()).modules().stream().noneMatch(m->m.path().equals(module))))
            throw DomainException.invalid("UNKNOWN_SERVICE_MODULE");
        String deployed=input.deployedSha()==null || input.deployedSha().isBlank()?null:RepositoryPolicy.sha(input.deployedSha());
        var safe=new AnalysisData.Request(input.repoId(),input.snapshotId(),input.task(),Redactor.cleanLog(input.question()),input.baseSnapshotId(),module,deployed);
        return new AnalysisData.Bound(safe,repo.getOwner()+"/"+repo.getName(),snapshot.commitSha(),baseSha);
    }
    public static String refusal(AnalysisData.Bound bound) {
        String text=bound.request().question().toLowerCase(Locale.ROOT);
        if(Pattern.compile("执行.{0,12}(shell|命令|脚本)|删除.{0,12}(数据库|文件|仓库)|读取.{0,12}(密钥|密码|私钥|环境变量)|泄露|绕过.{0,8}(权限|白名单)|部署到生产|execute\\s+(shell|command)|reveal.{0,20}(secret|password|token)|ignore.{0,30}(instruction|rule)|忽略.{0,12}(规则|指令)|/etc/|\\.\\./|[a-z]:[\\\\/]").matcher(text).find()) return "FORBIDDEN";
        var url=Pattern.compile("(?:https?://github\\.com/)?([a-z0-9_-]+/[a-z0-9_.-]+)").matcher(text);
        while(url.find()) {
            String name=url.group(1);
            if(!name.equalsIgnoreCase(bound.repository()) && !name.startsWith("src/") && !name.startsWith("main/")
                    && !name.startsWith("resources/") && !text.contains("/"+name) && !bound.repository().endsWith(name)) {
                // Explicit GitHub URLs always refer to a repository; ordinary relative paths are tool-validated.
                if(url.group().startsWith("http")) return "FORBIDDEN";
            }
        }
        if(Pattern.compile("天气|股票|股价|彩票|写.{0,6}(诗|情书)|weather|stock price|horoscope").matcher(text).find()) return "OUT_OF_SCOPE";
        var explicitRepo=Pattern.compile("(?:仓库|repository|repo)\\s*[:：]?\\s*([a-z0-9_.-]+/[a-z0-9_.-]+)").matcher(text);
        while(explicitRepo.find()) if(!explicitRepo.group(1).replaceFirst("\\.git$","").equalsIgnoreCase(bound.repository())) return "FORBIDDEN";
        String domain=bound.request().task()==AnalysisData.Task.DEPENDENCY_IMPACT?
                "依赖|模块|服务|升级|版本|影响|dependency|dependencies|module|service|upgrade|impact|pom":
                "告警|异常|报错|修复|堆栈|日志|alert|error|exception|fix|tracing|zipkin|failed|timeout";
        if(!Pattern.compile(domain).matcher(text).find()) return "OUT_OF_SCOPE";
        if(bound.request().task()==AnalysisData.Task.DEPENDENCY_IMPACT && bound.baseSha()==null) return "NEEDS_BASE_VERSION";
        return null;
    }
}
