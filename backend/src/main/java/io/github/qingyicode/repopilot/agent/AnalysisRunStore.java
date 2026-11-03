package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.repository.RepositoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AnalysisRunStore {
    private final JdbcTemplate jdbc;
    private final RepositoryStore repositories;
    private final JsonMapper json=JsonMapper.builder().build();
    private final String instanceId=java.util.UUID.randomUUID().toString();
    public AnalysisRunStore(JdbcTemplate jdbc,RepositoryStore repositories) {this.jdbc=jdbc;this.repositories=repositories;}
    public void create(AnalysisData.Bound bound,AnalysisData.Result result) {
        jdbc.update("INSERT INTO analysis_run(id,repo_id,snapshot_id,task,status,result_json,instance_id,lease_until) VALUES (?,?,?,?,?,?,?,TIMESTAMPADD(SECOND,45,CURRENT_TIMESTAMP(6)))",
                result.id(),bound.request().repoId(),bound.request().snapshotId(),bound.request().task().name(),result.status(),json.writeValueAsString(result),instanceId);
    }
    public boolean save(AnalysisData.Result result) {
        return jdbc.update("UPDATE analysis_run SET status=?,result_json=? WHERE id=? AND status='RUNNING' AND instance_id=?",result.status(),json.writeValueAsString(result),result.id(),instanceId)==1;
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
        // Only expired leases are interrupted: another local process may still own a live task.
        jdbc.update("UPDATE analysis_run SET status='REFUSED',result_json=JSON_SET(result_json,'$.status','REFUSED',"
                +"'$.reasonCode','PROCESS_RESTARTED','$.summary','任务所属进程已停止续租，请重新提交分析。') WHERE status='RUNNING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))");
    }
    @Scheduled(fixedDelay=15000)
    public void heartbeat() {
        jdbc.update("UPDATE analysis_run SET lease_until=TIMESTAMPADD(SECOND,45,CURRENT_TIMESTAMP(6)) WHERE status='RUNNING' AND instance_id=?",instanceId);
        recover();
    }
    public void interruptOwned() {
        jdbc.update("UPDATE analysis_run SET status='REFUSED',result_json=JSON_SET(result_json,'$.status','REFUSED',"
                +"'$.reasonCode','PROCESS_STOPPED','$.summary','服务停止中断了分析，请重新提交任务。') WHERE status='RUNNING' AND instance_id=?",instanceId);
    }
}
