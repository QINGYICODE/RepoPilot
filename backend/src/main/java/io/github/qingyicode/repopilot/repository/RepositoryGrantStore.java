package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RepositoryGrantStore {
    private final JdbcTemplate jdbc;
    public RepositoryGrantStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean allowed(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM repository_authorization WHERE full_name=? AND active=TRUE)",Boolean.class,name));
    }
    public void grant(String name,long githubId) {
        var ids=jdbc.queryForList("SELECT github_id FROM repository_authorization WHERE full_name=?",Long.class,name);
        if(!ids.isEmpty() && ids.getFirst()!=githubId) throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
        jdbc.update("INSERT INTO repository_authorization(full_name,github_id) VALUES (?,?) ON DUPLICATE KEY UPDATE active=IF(github_id=VALUES(github_id),TRUE,active)",name,githubId);
        if(jdbc.queryForObject("SELECT github_id FROM repository_authorization WHERE full_name=?",Long.class,name)!=githubId)
            throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
    }
    public void revoke(String name) {jdbc.update("UPDATE repository_authorization SET active=FALSE WHERE full_name=?",name);}
}
