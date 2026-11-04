package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GitHubApi implements GitHubGateway {
    private final List<HttpClient> clients;
    private final URI api;
    private final String token;
    private final Duration budget, requestTimeout;
    private final JsonMapper json=JsonMapper.builder().build();
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();
    private volatile Instant limitedUntil=Instant.EPOCH;
    private record Cached(RepositoryData.Metadata value,String etag,Instant expires) {}
    @org.springframework.beans.factory.annotation.Autowired
    public GitHubApi(RepositorySettings settings,RepositoryNetwork network) {
        this(network.routes().stream().map(RepositoryNetwork.Route::client).toList(),URI.create("https://api.github.com/"),settings.githubToken(),Duration.ofSeconds(18),Duration.ofSeconds(6));
    }
    // Alternative endpoints and small budgets are constructor-only for protocol tests.
    GitHubApi(HttpClient client,URI api,String token) {this(List.of(client),api,token,Duration.ofSeconds(18),Duration.ofSeconds(6));}
    GitHubApi(List<HttpClient> clients,URI api,String token,Duration budget,Duration requestTimeout) {
        this.clients=List.copyOf(clients);this.api=api;this.token=token;this.budget=budget;this.requestTimeout=requestTimeout;
    }
    @Override public RepositoryData.Metadata metadata(String owner,String name) {
        String key=RepositoryPolicy.fullName(owner,name);
        var previous=cache.get(key);
        if(previous!=null && previous.expires().isAfter(Instant.now())) return previous.value();
        var response=get("repos/"+key,previous==null?null:previous.etag());
        if(response.statusCode()==304 && previous!=null) {
            cache.put(key,new Cached(previous.value(),previous.etag(),Instant.now().plusSeconds(300)));return previous.value();
        }
        var body=parse(response);
        var result=new RepositoryData.Metadata(body.path("id").asLong(),body.path("full_name").asString(),body.path("default_branch").asString(),body.path("private").asBoolean(true));
        if(result.githubId()<=0 || !key.equalsIgnoreCase(result.fullName())) throw new DomainException(HttpStatus.FORBIDDEN,"GITHUB_IDENTITY_MISMATCH");
        if(result.privateRepository()) throw new DomainException(HttpStatus.FORBIDDEN,"GITHUB_PRIVATE_REPOSITORY_UNSUPPORTED");
        if(result.defaultBranch()==null || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/"+result.defaultBranch()))
            throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_INVALID_DEFAULT_BRANCH");
        cache.put(key,new Cached(result,response.headers().firstValue("ETag").orElse(null),Instant.now().plusSeconds(300)));
        return result;
    }
    @Override public String defaultHead(String owner,String name,String branch) {
        String key=RepositoryPolicy.fullName(owner,name);
        if(branch==null || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/"+branch)) throw DomainException.invalid("INVALID_ALLOWED_REF");
        String encoded=URLEncoder.encode(branch,StandardCharsets.UTF_8).replace("+","%20").replace("%2F","/");
        var body=parse(get("repos/"+key+"/git/ref/heads/"+encoded,null));
        if(!("refs/heads/"+branch).equals(body.path("ref").asString()) || !"commit".equals(body.path("object").path("type").asString()))
            throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_INVALID_HEAD_RESPONSE");
        try {return RepositoryPolicy.sha(body.path("object").path("sha").asString());}
        catch(DomainException invalid) {throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_INVALID_HEAD_RESPONSE");}
    }
    private JsonNode parse(HttpResponse<String> response) {
        if(response.body().length()>262144) throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_RESPONSE_TOO_LARGE");
        try {return json.readTree(response.body());}
        catch(RuntimeException invalid) {throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_INVALID_RESPONSE");}
    }
    private HttpResponse<String> get(String path,String etag) {
        if(limitedUntil.isAfter(Instant.now())) throw limited(Math.max(1,(int)Duration.between(Instant.now(),limitedUntil).toSeconds()+1));
        long deadline=System.nanoTime()+budget.toNanos();int route=0;
        for(int attempt=0;attempt<3;attempt++) {
            long remaining=deadline-System.nanoTime();if(remaining<=0) break;
            try {
                var builder=HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofNanos(Math.min(remaining,requestTimeout.toNanos())))
                        .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","RepoPilot");
                if(token!=null&&!token.isBlank()) builder.header("Authorization","Bearer "+token);
                if(etag!=null) builder.header("If-None-Match",etag);
                var response=clients.get(route).send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
                int status=response.statusCode();
                if(status==200 || status==304) return response;
                if(status==401) throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_AUTH_FAILED");
                if(status==403 || status==429) {
                    boolean quota=status==429 || response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")
                            || response.headers().firstValue("Retry-After").isPresent() || response.body().toLowerCase(Locale.ROOT).contains("secondary rate limit");
                    if(quota) {int seconds=retrySeconds(response);limitedUntil=Instant.now().plusSeconds(seconds);throw limited(seconds);}
                    throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_ACCESS_DENIED");
                }
                if(status==404) throw DomainException.missing("GITHUB_REPOSITORY_NOT_FOUND");
                if(status==409) throw DomainException.conflict("GITHUB_EMPTY_REPOSITORY");
                if(status==301 || status==302 || status==307 || status==308) throw DomainException.conflict("GITHUB_REPOSITORY_MOVED");
                if(status>=500) {if(attempt<2){pause(attempt,deadline);continue;}throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_UPSTREAM_ERROR");}
                throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_UNEXPECTED_RESPONSE");
            } catch(IOException failure) {
                if(attempt==2) break;
                // A transport failure may try the administrator's inherited proxy; quota/auth responses never change exits.
                route=Math.min(route+1,clients.size()-1);pause(attempt,deadline);
            } catch(InterruptedException failure) {
                Thread.currentThread().interrupt();throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_INTERRUPTED");
            }
        }
        throw new DomainException(HttpStatus.BAD_GATEWAY,"GITHUB_UNAVAILABLE");
    }
    private static DomainException limited(int seconds) {return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_RATE_LIMITED",seconds);}
    private static int retrySeconds(HttpResponse<?> response) {
        long seconds=60;
        try {
            var retry=response.headers().firstValue("Retry-After");
            if(retry.isPresent()) {
                try {seconds=Long.parseLong(retry.get());}
                catch(NumberFormatException date) {seconds=Duration.between(Instant.now(),ZonedDateTime.parse(retry.get(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()+1;}
            } else if(response.headers().firstValue("X-RateLimit-Reset").isPresent()) seconds=Long.parseLong(response.headers().firstValue("X-RateLimit-Reset").orElseThrow())-Instant.now().getEpochSecond()+1;
        } catch(RuntimeException invalid) {seconds=60;}
        return (int)Math.clamp(seconds,1,86400);
    }
    private static void pause(int attempt,long deadline) {
        long millis=Math.min(150L*(1L<<attempt),Math.max(0,(deadline-System.nanoTime())/1_000_000));
        try {if(millis>0) Thread.sleep(millis);}
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw DomainException.conflict("GITHUB_INTERRUPTED");}
    }
}
