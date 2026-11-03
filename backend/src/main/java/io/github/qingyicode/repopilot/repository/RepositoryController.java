package io.github.qingyicode.repopilot.repository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RepositoryController {
    private final SyncService sync;
    private final RepositoryStore repositories;
    public RepositoryController(SyncService sync, RepositoryStore repositories) {
        this.sync = sync; this.repositories = repositories;
    }
    public record RegisterRequest(@NotBlank String owner, @NotBlank String name) {}
    public record SyncRequest(@NotBlank String commitSha) {}
    @PostMapping("/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryRecord register(@Valid @RequestBody RegisterRequest request) { return sync.register(request.owner(),request.name()); }
    @GetMapping("/repositories")
    public List<RepositoryRecord> list() { return repositories.repositories(); }
    @PostMapping("/repositories/{repoId}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepositoryData.SyncJob sync(@PathVariable long repoId, @Valid @RequestBody SyncRequest request) { return sync.enqueue(repoId,request.commitSha()); }
    @GetMapping("/sync-jobs/{id}")
    public RepositoryData.SyncJob job(@PathVariable String id) { return repositories.job(id); }
    @GetMapping("/repositories/{repoId}/snapshots")
    public List<RepositoryData.Snapshot> snapshots(@PathVariable long repoId) { return repositories.snapshots(repoId); }
    @GetMapping("/repositories/{repoId}/snapshots/{snapshotId}/tree")
    public Object tree(@PathVariable long repoId,@PathVariable long snapshotId) { return sync.tree(repoId,snapshotId); }
}
