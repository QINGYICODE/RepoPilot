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
