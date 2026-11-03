package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.system.CapabilityService;
import io.github.qingyicode.repopilot.system.CapabilitySnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.annotation.Tool;

/** A per-probe, read-only tool. No general HTTP, filesystem or repository access. */
public final class CapabilityTools {
    private final CapabilityService capabilityService;
    private final AtomicInteger invocations = new AtomicInteger();
    private CapabilitySnapshot lastSnapshot;

    public CapabilityTools(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @Tool(description = "Read RepoPilot's actual phase, dependency versions, registered repository count and available capabilities. Read-only.")
    public CapabilitySnapshot getProjectCapabilities() {
        invocations.incrementAndGet();
        lastSnapshot = capabilityService.snapshot();
        return lastSnapshot;
    }

    public int invocations() {
        return invocations.get();
    }

    public CapabilitySnapshot lastSnapshot() {
        return lastSnapshot;
    }
}
