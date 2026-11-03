package io.github.qingyicode.repopilot.dependency;

import io.github.qingyicode.repopilot.indexing.IndexStore;
import org.springframework.stereotype.Service;

@Service
public class DependencyService {
    private final IndexStore indices;
    private final ImpactAnalyzer analyzer;
    public DependencyService(IndexStore indices, ImpactAnalyzer analyzer) { this.indices = indices; this.analyzer = analyzer; }
    public DependencyData.Result compare(long repoId, long base, long head, String module, int depth) {
        return analyzer.compare(base,indices.graph(repoId,base),head,indices.graph(repoId,head),module,depth);
    }
}
