package io.github.qingyicode.repopilot.common;

import java.nio.file.Path;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import io.github.qingyicode.repopilot.config.RepositorySettings;

@Component
public class RepositoryPolicy {
    private final RepositorySettings settings;
    public RepositoryPolicy(RepositorySettings settings) { this.settings = settings; }
    public String allowedName(String owner, String name) {
        if (owner == null || name == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || name.endsWith(".git")) {
            throw DomainException.invalid("INVALID_REPOSITORY_NAME");
        }
        String fullName = (owner + "/" + name).toLowerCase(Locale.ROOT);
        if (settings.allowedRepositories().stream().noneMatch(fullName::equalsIgnoreCase)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "REPOSITORY_NOT_ALLOWED");
        }
        return fullName;
    }
    public static String sha(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{40}")) throw DomainException.invalid("FULL_COMMIT_SHA_REQUIRED");
        return value.toLowerCase(Locale.ROOT);
    }
    public static String path(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.contains("\\")
                || value.indexOf('\0') >= 0 || value.startsWith("/") || value.contains(":")
                || Path.of(value).isAbsolute()) throw DomainException.invalid("INVALID_REPOSITORY_PATH");
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.equals(".git")) {
                throw DomainException.invalid("INVALID_REPOSITORY_PATH");
            }
        }
        return value;
    }
}
