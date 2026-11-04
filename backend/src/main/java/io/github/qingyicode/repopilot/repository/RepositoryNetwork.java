package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Administrator configuration only. A model or HTTP request cannot choose a network destination or proxy. */
@Component
public class RepositoryNetwork {
    public record Route(String name, Proxy proxy) {
        public HttpClient client() {
            var selector=new ProxySelector() {
                public List<Proxy> select(URI uri) {return List.of(proxy);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error) {}
            };
            return HttpClient.newBuilder().proxy(selector).connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
    }
    private final List<Route> routes;
    @Autowired public RepositoryNetwork(RepositorySettings settings) {this(settings,System.getenv());}
    RepositoryNetwork(RepositorySettings settings,Map<String,String> environment) {
        if(!settings.networkProxy().isBlank()) {routes=List.of(new Route("EXPLICIT_PROXY",settings.proxy()));return;}
        var values=new ArrayList<Route>();values.add(new Route("DIRECT",Proxy.NO_PROXY));
        for(String key:List.of("HTTPS_PROXY","https_proxy","HTTP_PROXY","http_proxy")) {
            try {
                String value=environment.get(key);if(value==null || value.isBlank()) continue;
                var uri=URI.create(value);
                if(!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                        || uri.getQuery()!=null || uri.getFragment()!=null || (uri.getPath()!=null&&!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))) continue;
                int port=uri.getPort()==-1?80:uri.getPort();if(port<1 || port>65535) continue;
                values.add(new Route("ENVIRONMENT_PROXY",new Proxy(Proxy.Type.HTTP,new InetSocketAddress(uri.getHost(),port))));break;
            } catch(IllegalArgumentException ignored) { /* Never expose a proxy URL, which may contain credentials. */ }
        }
        routes=List.copyOf(values);
    }
    public List<Route> routes() {return routes;}
    public String mode() {return routes.size()>1?"DIRECT_THEN_ENVIRONMENT_PROXY":routes.getFirst().name();}
}
