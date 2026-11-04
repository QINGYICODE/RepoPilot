package io.github.qingyicode.repopilot.common;

import java.nio.file.Path;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import io.github.qingyicode.repopilot.config.RepositorySettings;

@Component
public class RepositoryPolicy {
    private final RepositorySettings settings;
    private final io.github.qingyicode.repopilot.repository.RepositoryGrantStore grants;
    public RepositoryPolicy(RepositorySettings settings,io.github.qingyicode.repopilot.repository.RepositoryGrantStore grants) { this.settings = settings;this.grants=grants; }
    public static String fullName(String owner,String name) {
        if (owner == null || name == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || name.endsWith(".git")) {
            throw DomainException.invalid("INVALID_REPOSITORY_NAME");
        }
        return (owner + "/" + name).toLowerCase(Locale.ROOT);
    }
    public boolean seeded(String fullName) {return settings.allowedRepositories().stream().anyMatch(fullName::equalsIgnoreCase);}
    public String allowedName(String owner, String name) {
        String fullName=fullName(owner,name);
        if (!seeded(fullName) && !grants.allowed(fullName)) {
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
