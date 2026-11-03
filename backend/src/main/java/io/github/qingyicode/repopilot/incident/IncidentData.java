package io.github.qingyicode.repopilot.incident;

import java.util.List;
import java.util.Map;
import io.github.qingyicode.repopilot.repository.RepositoryData.Evidence;

public final class IncidentData {
    private IncidentData() {}
    public record Candidate(String commitSha, String subject, String classification, String applicability,
                            int score, List<String> matchedTerms, List<Evidence> evidence) {}
    public record Result(String status, String reasonCode, String knowledgeSha, String deployedSha,
                         List<String> signatures, List<Candidate> candidates, List<String> warnings,
                         Map<String,Object> coverage) {}
}
