package io.github.qingyicode.repopilot.system;

import java.util.List;
import java.util.Map;

public record CapabilitySnapshot(
        String application,
        String phase,
        Map<String, String> versions,
        long repositoryCount,
        boolean liveModelEnabled,
        List<String> availableCapabilities,
        List<String> plannedCapabilities) {
}
