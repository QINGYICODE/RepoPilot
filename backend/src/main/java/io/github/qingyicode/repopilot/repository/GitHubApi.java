package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GitHubApi implements GitHubGateway {
    private final HttpClient client;
    private final URI api;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(RepositoryData.Metadata value, String etag, Instant expires) {}

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubApi(RepositorySettings settings) {
        this(client(settings), URI.create("https://api.github.com/"), settings.githubToken());
    }
    private static HttpClient client(RepositorySettings settings) {
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        if (!settings.networkProxy().isBlank()) builder.proxy(java.net.ProxySelector.of((java.net.InetSocketAddress)settings.proxy().address()));
        return builder.build();
    }

    // The alternative endpoint is constructor-only for local protocol tests, never a public request parameter.
    GitHubApi(HttpClient client, URI api, String token) {
        this.client = client; this.api = api; this.token = token;
    }

    @Override
    public RepositoryData.Metadata metadata(String owner, String name) {
        String key = owner + "/" + name;
        Cached previous = cache.get(key);
        if (previous != null && previous.expires().isAfter(Instant.now())) return previous.value();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(api.resolve("repos/" + key)).timeout(Duration.ofSeconds(25))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "RepoPilot");
                if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
                if (previous != null && previous.etag() != null) request.header("If-None-Match", previous.etag());
                var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 304 && previous != null) {
                    cache.put(key, new Cached(previous.value(), previous.etag(), Instant.now().plusSeconds(300)));
                    return previous.value();
                }
                if (status == 403 || status == 429) throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                        response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0") || status == 429
                                ? "GITHUB_RATE_LIMITED" : "GITHUB_ACCESS_DENIED");
                if (status == 404) throw DomainException.missing("GITHUB_REPOSITORY_NOT_FOUND");
                if (status == 401) throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_AUTH_FAILED");
                if (status >= 500 && attempt < 2) { pause(attempt); continue; }
                if (status != 200) throw new DomainException(HttpStatus.BAD_GATEWAY, "GITHUB_UNEXPECTED_RESPONSE");
                if (response.body().length() > 262144) throw new DomainException(HttpStatus.BAD_GATEWAY, "GITHUB_RESPONSE_TOO_LARGE");
                var body = json.readTree(response.body());
                var result = new RepositoryData.Metadata(body.path("id").asLong(), body.path("full_name").asString(),
                        body.path("default_branch").asString(), body.path("private").asBoolean(true));
                if (result.githubId() <= 0 || !key.equalsIgnoreCase(result.fullName()) || result.privateRepository()
                        || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + result.defaultBranch())) {
                    throw new DomainException(HttpStatus.FORBIDDEN, "GITHUB_IDENTITY_OR_VISIBILITY_MISMATCH");
                }
                cache.put(key, new Cached(result, response.headers().firstValue("ETag").orElse(null), Instant.now().plusSeconds(300)));
                return result;
            } catch (IOException error) {
                if (attempt == 2) throw new DomainException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE");
                pause(attempt);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INTERRUPTED");
            }
        }
        throw new DomainException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE");
    }

    private static void pause(int attempt) {
        try { Thread.sleep(200L * (attempt + 1)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw DomainException.conflict("SYNC_INTERRUPTED"); }
    }
}
