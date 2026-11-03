package io.github.qingyicode.repopilot.indexing;

import static io.github.qingyicode.repopilot.repository.RepositoryData.*;
import io.github.qingyicode.repopilot.common.Hashes;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public final class EvidenceFactory {
    private EvidenceFactory() {}
    public static Evidence create(long repoId, String fullName, String type, String sha, String parentSha,
                                  String path, String symbol, String module, int start, int end,
                                  Map<String,Object> locator, String raw, boolean truncated) {
        String safe = Redactor.clean(raw);
        if (safe.length() > 12000) { safe = safe.substring(0, 12000); truncated = true; }
        String hash = Hashes.sha256(safe);
        String id = Hashes.sha256(repoId + "|" + RepositorySettings.INDEX_VERSION + "|" + type + "|" + sha + "|"
                + parentSha + "|" + path + "|" + symbol + "|" + start + "|" + end + "|" + locator + "|" + hash);
        String source = "https://github.com/" + fullName;
        if (type.equals("COMMIT")) source += "/commit/" + sha;
        else if (type.equals("DIFF")) source += "/commit/" + sha;
        else source += "/blob/" + sha + "/" + encodedPath(path) + "#L" + start + "-L" + end;
        return new Evidence(id, repoId, type, sha, parentSha, path, symbol, module, start, end,
                locator, hash, safe, source, truncated);
    }
    public static String range(String body, int start, int end) {
        var lines = body.split("\n", -1);
        if (start < 1 || end < start || start > lines.length) return "";
        return String.join("\n", Arrays.copyOfRange(lines, start - 1, Math.min(end, lines.length)));
    }
    public static String encodedPath(String path) {
        return Arrays.stream(path.split("/")).map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }
}
