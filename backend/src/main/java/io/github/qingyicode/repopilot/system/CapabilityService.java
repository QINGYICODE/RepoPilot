package io.github.qingyicode.repopilot.system;

import io.github.qingyicode.repopilot.repository.RepositoryMapper;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class CapabilityService {
    private final RepositoryMapper repositoryMapper;
    private final BuildProperties build;
    private final Environment environment;

    public CapabilityService(RepositoryMapper repositoryMapper, BuildProperties build, Environment environment) {
        this.repositoryMapper = repositoryMapper;
        this.build = build;
        this.environment = environment;
    }

    public CapabilitySnapshot snapshot() {
        return new CapabilitySnapshot(
                "RepoPilot", "D7",
                Map.of("java", System.getProperty("java.version"),
                        "springBoot", SpringBootVersion.getVersion(),
                        "springAi", build.get("spring-ai"),
                        "mybatisPlus", build.get("mybatis-plus")),
                repositoryMapper.selectCount(null), environment.acceptsProfiles(Profiles.of("llm")),
                List.of("MYSQL_PERSISTENCE", "FLYWAY_MIGRATION", "READ_ONLY_CAPABILITY_TOOL", "REPOSITORY_SYNC", "CODE_EVIDENCE_SEARCH", "DEPENDENCY_IMPACT",
                        "HISTORICAL_FIX_SEARCH", "BOUNDED_AGENT_LOOP", "EVIDENCE_GATE", "ANALYSIS_RUN_TRACE"),
                List.of("CONFIRMED_INCIDENT_LINK", "EQUIVALENT_PATCH_CHECK"));
    }
}
