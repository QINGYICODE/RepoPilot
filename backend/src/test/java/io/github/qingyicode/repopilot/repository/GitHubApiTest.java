package io.github.qingyicode.repopilot.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitHubApiTest {
    @Test void secondaryLimitCarriesRetryAdviceAndStopsFurtherRequests() throws Exception {
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{count.incrementAndGet();exchange.getResponseHeaders().set("Retry-After","120");exchange.sendResponseHeaders(403,-1);exchange.close();});server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"");
            for(int i=0;i<2;i++) assertThatThrownBy(()->client.metadata("fixture","repo")).isInstanceOfSatisfying(io.github.qingyicode.repopilot.common.DomainException.class,
                    e->{assertThat(e.code()).isEqualTo("GITHUB_RATE_LIMITED");assertThat(e.retryAfterSeconds()).isBetween(119,121);});
            assertThat(count.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void transportFailureCanTryTheNextConfiguredRouteWithinTheBudget() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{byte[] body="{\"id\":7,\"full_name\":\"fixture/repo\",\"default_branch\":\"main\",\"private\":false}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
        try {
            var unavailable=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(100)).proxy(java.net.ProxySelector.of(new InetSocketAddress("127.0.0.1",1))).build();
            var client=new GitHubApi(java.util.List.of(unavailable,HttpClient.newHttpClient()),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"",java.time.Duration.ofSeconds(2),java.time.Duration.ofMillis(500));
            assertThat(client.metadata("fixture","repo").githubId()).isEqualTo(7);
        } finally {server.stop(0);}
    }
    @Test void requestDeadlineIsBoundedAndAnInvalidTokenIsNotRetried() throws Exception {
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{count.incrementAndGet();exchange.sendResponseHeaders(401,-1);exchange.close();});server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"bad-test-token");
            assertThatThrownBy(()->client.metadata("fixture","repo")).hasMessage("GITHUB_AUTH_FAILED");assertThat(count.get()).isEqualTo(1);
            var unavailable=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(50)).proxy(java.net.ProxySelector.of(new InetSocketAddress("127.0.0.1",1))).build();
            long before=System.nanoTime();
            var bounded=new GitHubApi(java.util.List.of(unavailable),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"",java.time.Duration.ofMillis(250),java.time.Duration.ofMillis(100));
            assertThatThrownBy(()->bounded.metadata("fixture","repo")).hasMessage("GITHUB_UNAVAILABLE");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-before)).isLessThan(java.time.Duration.ofSeconds(2));
        } finally {server.stop(0);}
    }
    @Test void resolvesOnlyTheExactDefaultHeadAndDoesNotFollowRedirects() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/repos/fixture/repo/git/ref/heads/main",exchange->{byte[] body=("{\"ref\":\"refs/heads/main\",\"object\":{\"type\":\"commit\",\"sha\":\""+"a".repeat(40)+"\"}}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
        server.createContext("/repos/fixture/moved",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/private");exchange.sendResponseHeaders(301,-1);exchange.close();});server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"");
            assertThat(client.defaultHead("fixture","repo","main")).isEqualTo("a".repeat(40));
            assertThatThrownBy(()->client.metadata("fixture","moved")).hasMessage("GITHUB_REPOSITORY_MOVED");
        } finally {server.stop(0);}
    }
    @Test void metadataIsValidatedAndCached() throws Exception {
        var count=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/repos/fixture/repo",exchange->{
            count.incrementAndGet(); byte[] body="{\"id\":7,\"full_name\":\"fixture/repo\",\"default_branch\":\"main\",\"private\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag","test"); exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        }); server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"");
            assertThat(client.metadata("fixture","repo").githubId()).isEqualTo(7);
            assertThat(client.metadata("fixture","repo").fullName()).isEqualTo("fixture/repo");
            assertThat(count.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void rateLimitIsAnErrorRatherThanAnEmptyResult() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getResponseHeaders().set("X-RateLimit-Remaining","0");exchange.sendResponseHeaders(403,-1);exchange.close();});server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"");
            assertThatThrownBy(()->client.metadata("fixture","repo")).hasMessage("GITHUB_RATE_LIMITED");
        } finally {server.stop(0);}
    }
    @Test void transientServerFailureIsRetriedWithinTheFixedBudget() throws Exception {
        var attempts=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            if(attempts.incrementAndGet()==1){exchange.sendResponseHeaders(502,-1);exchange.close();return;}
            byte[] body="{\"id\":7,\"full_name\":\"fixture/repo\",\"default_branch\":\"main\",\"private\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        try {
            var client=new GitHubApi(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"");
            assertThat(client.metadata("fixture","repo").githubId()).isEqualTo(7);assertThat(attempts.get()).isEqualTo(2);
        }finally{server.stop(0);}
    }
}
