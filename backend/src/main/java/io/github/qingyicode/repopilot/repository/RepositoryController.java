package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.dependency.DependencyData;
import io.github.qingyicode.repopilot.dependency.DependencyService;
import io.github.qingyicode.repopilot.indexing.IndexStore;
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
    private final IndexStore indices;
    private final DependencyService dependencies;
    public RepositoryController(SyncService sync, RepositoryStore repositories, IndexStore indices, DependencyService dependencies) {
        this.sync = sync; this.repositories = repositories; this.indices = indices; this.dependencies = dependencies;
    }
    public record RegisterRequest(@NotBlank String owner, @NotBlank String name) {}
    public record SyncRequest(@NotBlank String commitSha) {}
    public record CompareRequest(@NotNull @Positive Long baseSnapshotId, @NotNull @Positive Long headSnapshotId, String module, Integer maxDepth) {}
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
    @GetMapping("/repositories/{repoId}/snapshots/{snapshotId}/files")
    public Object files(@PathVariable long repoId, @PathVariable long snapshotId, @RequestParam(required=false) String path,
                        @RequestParam(defaultValue="1") int startLine, @RequestParam(defaultValue="80") int endLine) {
        return path == null ? indices.files(repoId,snapshotId) : indices.file(repoId,snapshotId,path,startLine,endLine);
    }
    @GetMapping("/repositories/{repoId}/snapshots/{snapshotId}/search")
    public RepositoryData.SearchPage search(@PathVariable long repoId, @PathVariable long snapshotId, @RequestParam String q,
            @RequestParam(required=false) String type, @RequestParam(required=false) String path, @RequestParam(required=false) String symbol,
            @RequestParam(required=false) String sourceSha, @RequestParam(required=false) String module,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return indices.search(repoId,snapshotId,q,type,path,symbol,sourceSha,module,limit,cursor);
    }
    @GetMapping("/repositories/{repoId}/snapshots/{snapshotId}/evidence/{id}")
    public RepositoryData.Evidence evidence(@PathVariable long repoId, @PathVariable long snapshotId, @PathVariable String id) { return indices.evidence(repoId,snapshotId,id); }
    @GetMapping("/repositories/{repoId}/snapshots/{snapshotId}/dependency-graph")
    public DependencyData.Graph graph(@PathVariable long repoId, @PathVariable long snapshotId) { return indices.graph(repoId,snapshotId); }
    @PostMapping("/repositories/{repoId}/dependency-impact")
    public DependencyData.Result compare(@PathVariable long repoId, @Valid @RequestBody CompareRequest request) {
        return dependencies.compare(repoId,request.baseSnapshotId(),request.headSnapshotId(),request.module(),request.maxDepth() == null ? 8 : request.maxDepth());
    }
}
