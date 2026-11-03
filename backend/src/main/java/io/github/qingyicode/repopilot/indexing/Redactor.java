package io.github.qingyicode.repopilot.indexing;

import java.util.regex.Pattern;

public final class Redactor {
    private Redactor() {}
    private static final Pattern TOKENS = Pattern.compile("(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?im)((?:api[_-]?key|password|access[_-]?token|client[_-]?secret)\\s*[:=]\\s*[\"'])([^\"'\\r\\n]{6,})([\"'])");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?-----END [^-\\r\\n]*PRIVATE KEY-----");
    public static String cleanLog(String source) {
        return clean(source).replaceAll("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:password|api[_-]?key|access[_-]?token|client[_-]?secret)\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]");
    }
    public static String clean(String source) {
        String cleaned = TOKENS.matcher(source).replaceAll("[REDACTED]");
        var assignments = ASSIGNMENT.matcher(cleaned); var output = new StringBuilder();
        while (assignments.find()) {
            String value = assignments.group(2);
            String replacement = value.startsWith("${") || value.startsWith("<") ? assignments.group()
                    : assignments.group(1) + "[REDACTED]" + assignments.group(3);
            assignments.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        assignments.appendTail(output);
        var keys = PRIVATE_KEY.matcher(output.toString()); var safe = new StringBuilder();
        while (keys.find()) {
            String replacement = "[REDACTED_PRIVATE_KEY]" + "\n".repeat((int) keys.group().chars().filter(c -> c == '\n').count());
            keys.appendReplacement(safe, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        keys.appendTail(safe); return safe.toString();
    }
}
