package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.Hashes;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionStore {
    private final JdbcTemplate jdbc;
    private final String instance=UUID.randomUUID().toString();
    public ConnectionStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public ConnectionJob enqueue(RepositoryAddress address,boolean authorize,boolean autoImport) {
        String id=UUID.randomUUID().toString(),key=Hashes.sha256(address.fullName()+"|"+authorize+"|"+autoImport);
        jdbc.update("INSERT IGNORE INTO repository_connection(id,owner,name,authorize_access,auto_import,status,active_key,instance_id,lease_until) "
                +"VALUES (?,?,?,?,?,'QUEUED',?,?,TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)))",id,address.owner(),address.name(),authorize,autoImport,key,instance);
        var rows=jdbc.query("SELECT * FROM repository_connection WHERE id=? OR active_key=? ORDER BY created_at DESC LIMIT 1",this::row,id,key);
        if(rows.isEmpty()) throw DomainException.conflict("CONNECTION_QUEUE_WRITE_FAILED");return rows.getFirst();
    }
    public ConnectionJob get(String id) {
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw DomainException.invalid("INVALID_CONNECTION_ID");
        var rows=jdbc.query("SELECT * FROM repository_connection WHERE id=?",this::row,id);
        if(rows.isEmpty()) throw DomainException.missing("CONNECTION_NOT_FOUND");return rows.getFirst();
    }
    public List<ConnectionJob> queued() {
        return jdbc.query("SELECT * FROM repository_connection WHERE status='QUEUED' AND (instance_id=? OR lease_until<CURRENT_TIMESTAMP(6)) ORDER BY created_at LIMIT 8",this::row,instance);
    }
    public boolean claim(String id) {
        return jdbc.update("UPDATE repository_connection SET status='VERIFYING',instance_id=?,attempts=attempts+1,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)) "
                +"WHERE id=? AND status='QUEUED' AND (instance_id=? OR lease_until<CURRENT_TIMESTAMP(6))",instance,id,instance)==1;
    }
    public void registered(String id,long repoId) {
        jdbc.update("UPDATE repository_connection SET repo_id=?,status='RESOLVING' WHERE id=? AND instance_id=? AND status='VERIFYING'",repoId,id,instance);
    }
    public void ready(String id,String syncJobId) {
        jdbc.update("UPDATE repository_connection SET status='READY',sync_job_id=?,error_code=NULL,retry_at=NULL,active_key=NULL WHERE id=? AND instance_id=? AND status IN ('VERIFYING','RESOLVING')",syncJobId,id,instance);
    }
    public void fail(String id,String code,Integer retryAfter) {
        jdbc.update("UPDATE repository_connection SET status='FAILED',error_code=?,retry_at=IF(? IS NULL,NULL,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))),active_key=NULL WHERE id=? AND instance_id=? AND status IN ('VERIFYING','RESOLVING')",code,retryAfter,retryAfter,id,instance);
    }
    public void maintain() {
        jdbc.update("UPDATE repository_connection SET lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)) WHERE instance_id=? AND status IN ('QUEUED','VERIFYING','RESOLVING')",instance);
        jdbc.update("UPDATE repository_connection SET status=IF(attempts<3,'QUEUED','FAILED'),error_code='CONNECTION_LEASE_EXPIRED',active_key=IF(attempts<3,active_key,NULL) "
                +"WHERE status IN ('VERIFYING','RESOLVING') AND lease_until<CURRENT_TIMESTAMP(6)");
    }
    public void stop() {
        jdbc.update("UPDATE repository_connection SET status='FAILED',error_code='CONNECTION_INTERRUPTED',active_key=NULL WHERE instance_id=? AND status IN ('QUEUED','VERIFYING','RESOLVING')",instance);
    }
    private ConnectionJob row(ResultSet rs,int row) throws SQLException {
        Timestamp retry=rs.getTimestamp("retry_at");
        return new ConnectionJob(rs.getString("id"),rs.getString("owner"),rs.getString("name"),rs.getBoolean("authorize_access"),rs.getBoolean("auto_import"),
                rs.getObject("repo_id",Long.class),rs.getString("sync_job_id"),rs.getString("status"),rs.getInt("attempts"),rs.getString("error_code"),retry==null?null:retry.toInstant().toString(),rs.getTimestamp("updated_at").toInstant().toString());
    }
}
