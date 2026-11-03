package io.github.qingyicode.repopilot.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final CapabilityService capabilityService;

    public SystemController(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping("/capabilities")
    public CapabilitySnapshot capabilities() {
        return capabilityService.snapshot();
    }
}
