package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.repository.RepositoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AnalysisRunStore {
    private final JdbcTemplate jdbc;
    private final RepositoryStore repositories;
    private final JsonMapper json=JsonMapper.builder().build();
    public AnalysisRunStore(JdbcTemplate jdbc,RepositoryStore repositories) {this.jdbc=jdbc;this.repositories=repositories;}
    public void create(AnalysisData.Bound bound,AnalysisData.Result result) {
        jdbc.update("INSERT INTO analysis_run(id,repo_id,snapshot_id,task,status,result_json) VALUES (?,?,?,?,?,?)",
                result.id(),bound.request().repoId(),bound.request().snapshotId(),bound.request().task().name(),result.status(),json.writeValueAsString(result));
    }
    public void save(AnalysisData.Result result) {
        jdbc.update("UPDATE analysis_run SET status=?,result_json=? WHERE id=? AND status='RUNNING'",result.status(),json.writeValueAsString(result),result.id());
    }
    public AnalysisData.Result get(String id) {
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw DomainException.invalid("INVALID_RUN_ID");
        var rows=jdbc.query("SELECT repo_id,result_json FROM analysis_run WHERE id=?",(rs,row)-> {
            repositories.repository(rs.getLong(1));return json.readValue(rs.getString(2),AnalysisData.Result.class);
        },id);
        if(rows.isEmpty()) throw DomainException.missing("ANALYSIS_RUN_NOT_FOUND");
        return rows.getFirst();
    }
    public void recover() {
        // No implicit model retry after process restart: retain trace and mark interrupted tasks explicitly.
        jdbc.update("UPDATE analysis_run SET status='REFUSED',result_json=JSON_SET(result_json,'$.status','REFUSED',"
                +"'$.reasonCode','PROCESS_RESTARTED','$.summary','服务重启中断了分析，请重新提交任务。') WHERE status='RUNNING'");
    }
}
