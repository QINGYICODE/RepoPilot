package io.github.qingyicode.repopilot.incident;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories/{repoId}/fix-candidates")
public class IncidentController {
    private final IncidentService incidents;
    public IncidentController(IncidentService incidents) {this.incidents=incidents;}
    public record Request(@NotNull @Positive Long snapshotId, String service, @NotBlank @Size(max=8000) String message, String deployedSha) {}
    @PostMapping
    public IncidentData.Result search(@PathVariable long repoId,@Valid @RequestBody Request request) {
        return incidents.search(repoId,request.snapshotId(),request.service(),request.message(),request.deployedSha());
    }
}
