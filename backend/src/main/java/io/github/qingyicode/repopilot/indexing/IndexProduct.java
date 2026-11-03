package io.github.qingyicode.repopilot.indexing;

import io.github.qingyicode.repopilot.dependency.DependencyData.Graph;
import io.github.qingyicode.repopilot.repository.RepositoryData.*;
import java.util.List;
import java.util.Map;

public record IndexProduct(List<Blob> blobs, List<IndexedFile> files, List<Evidence> evidence,
                           List<Commit> commits, Graph graph, Map<String,Object> coverage, Map<String,Object> stats) {}
