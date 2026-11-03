package io.github.qingyicode.repopilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.qingyicode.repopilot.agent.CapabilityProbeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("llm")
@EnabledIfSystemProperty(named = "repopilot.live-llm", matches = "true")
class LiveModelIT {
    @Autowired CapabilityProbeService probe;

    @Test
    void realModelRequestsToolAndGeneratesEvidenceBackedJson() {
        var result = probe.verify();
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.phase()).isEqualTo("D4");
    }
}
