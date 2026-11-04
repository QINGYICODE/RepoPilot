package io.github.qingyicode.repopilot.repository;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryNetworkTest {
    private RepositorySettings settings(String proxy) {
        return new RepositorySettings(Path.of("target/network-test"),List.of("fixture/repo"),"",5000,262144,2000,40000,180,false,proxy);
    }
    @Test void inheritedProxyIsOnlyATransportFallback() {
        var network=new RepositoryNetwork(settings(""),Map.of("HTTPS_PROXY","http://127.0.0.1:8123"));
        assertThat(network.mode()).isEqualTo("DIRECT_THEN_ENVIRONMENT_PROXY");
        assertThat(network.routes()).extracting(RepositoryNetwork.Route::name).containsExactly("DIRECT","ENVIRONMENT_PROXY");
        assertThat(network.routes().getFirst().proxy()).isEqualTo(Proxy.NO_PROXY);
    }
    @Test void explicitProxyHasNoSilentDirectOrInheritedFallback() {
        var network=new RepositoryNetwork(settings("http://127.0.0.1:8123"),Map.of("HTTPS_PROXY","http://127.0.0.1:8124"));
        assertThat(network.routes()).extracting(RepositoryNetwork.Route::name).containsExactly("EXPLICIT_PROXY");
    }
    @Test void malformedOrCredentialBearingEnvironmentProxiesAreIgnored() {
        for(String value:List.of("http://user:secret@localhost:80","file:///tmp/proxy","http://localhost:80/path","http://localhost:80#token")) {
            var network=new RepositoryNetwork(settings(""),Map.of("HTTPS_PROXY",value));
            assertThat(network.routes()).extracting(RepositoryNetwork.Route::name).containsExactly("DIRECT");
        }
    }
}
