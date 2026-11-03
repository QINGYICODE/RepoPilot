package io.github.qingyicode.repopilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.qingyicode.repopilot.repository.RepositoryMapper;
import io.github.qingyicode.repopilot.repository.RepositoryRecord;
import io.github.qingyicode.repopilot.system.CapabilitySnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "repopilot.mysql-it", matches = "true")
class RepositoryPersistenceIT {
    @Autowired RepositoryMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test
    void appliesFlywayAndPerformsCrudAgainstRealMySql() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isGreaterThanOrEqualTo(8);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Long.class))
                .isGreaterThanOrEqualTo(1L);
        var record = new RepositoryRecord();
        record.setOwner("d1-test-" + UUID.randomUUID());
        record.setName("probe");
        record.setDefaultRef("main");
        try {
            assertThat(mapper.insert(record)).isEqualTo(1);
            assertThat(record.getId()).isPositive();
            assertThat(mapper.selectById(record.getId()).getDefaultRef()).isEqualTo("main");
            record.setDefaultRef("release/test");
            assertThat(mapper.updateById(record)).isEqualTo(1);
            assertThat(mapper.selectOne(new QueryWrapper<RepositoryRecord>().eq("owner", record.getOwner()))
                    .getDefaultRef()).isEqualTo("release/test");
            assertThat(mapper.deleteById(record.getId())).isEqualTo(1);
            assertThat(mapper.selectById(record.getId())).isNull();
        } finally {
            if (record.getId() != null) mapper.deleteById(record.getId());
        }
    }

    @Test
    void exposesDatabaseBackedCapabilitiesAndHealthWithoutAModelKey() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var capabilities = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/system/capabilities"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(capabilities.statusCode()).isEqualTo(200);
            var snapshot = JsonMapper.builder().build().readValue(capabilities.body(), CapabilitySnapshot.class);
            assertThat(snapshot.phase()).isEqualTo("D1");
            assertThat(snapshot.liveModelEnabled()).isFalse();
            assertThat(snapshot.versions()).containsEntry("springBoot", "4.1.2").containsEntry("springAi", "2.0.2");
            var health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("UP");
        }
    }
}
