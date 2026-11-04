package io.github.qingyicode.repopilot.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.qingyicode.repopilot.common.Hashes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PomAnalyzerTest {
    private final PomAnalyzer parser = new PomAnalyzer();
    private final ImpactAnalyzer impact = new ImpactAnalyzer();
    public static Map<String,String> files(String libraryVersion) {
        var result = new LinkedHashMap<String,String>();
        result.put("pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
                  <properties><library.version>%s</library.version></properties>
                  <modules><module>lib</module><module>api</module><module>batch</module></modules>
                </project>
                """.formatted(libraryVersion));
        result.put("lib/pom.xml", child("lib","<dependencies><dependency><groupId>external</groupId><artifactId>core</artifactId><version>${library.version}</version></dependency></dependencies>"));
        result.put("api/pom.xml", child("api","<dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>"));
        result.put("batch/pom.xml", child("batch","<dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>"));
        result.put("api/src/main/resources/application.yml","spring:\n  application:\n    name: api\n");
        result.put("batch/src/main/resources/application.yml","spring:\n  application:\n    name: batch\n");
        return result;
    }
    public static String child(String artifact, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>"+artifact+"</artifactId>"+body+"</project>";
    }
    private DependencyData.Graph graph(Map<String,String> files) {
        return parser.analyze(files,s -> new DependencyData.Source(s.path(),s.startLine(),s.endLine(),Hashes.sha256(s.toString())));
    }
    @Test void followsReverseConsumerPathsAndIncludesVersionPropertyProof() {
        var before = graph(files("1")); var after = graph(files("2"));
        var result = impact.compare(1,before,2,after,null,8);
        assertThat(result.affectedServices()).containsExactly("api","batch");
        assertThat(result.changes()).hasSize(1);
        assertThat(result.impacts()).anySatisfy(i -> {
            assertThat(i.path()).containsExactly("api","lib","external:core@2");
            assertThat(i.view()).isEqualTo("HEAD"); assertThat(i.evidenceIds()).hasSizeGreaterThanOrEqualTo(3);
        });
        assertThat(after.modules().stream().filter(m->m.path().equals("lib")).findFirst().orElseThrow().dependencies().getFirst().sources())
                .anySatisfy(s -> assertThat(s.path()).isEqualTo("pom.xml"));
    }
    @Test void discoversBackendReactorWithoutRepositoryRootPomAndPreservesItsModulePaths() {
        var before = new LinkedHashMap<String,String>(); var after = new LinkedHashMap<String,String>();
        files("1").forEach((path, body) -> before.put("backend/" + path, body));
        files("2").forEach((path, body) -> after.put("backend/" + path, body));
        after.put("frontend/package.json", "{}");
        var parsed = graph(after);
        assertThat(parsed.modules()).extracting(DependencyData.Module::path)
                .containsExactlyInAnyOrder("backend", "backend/lib", "backend/api", "backend/batch");
        assertThat(parsed.warnings()).contains("MAVEN_ROOTS_DISCOVERED_FROM_SUBDIRECTORIES");
        assertThat(impact.compare(1,graph(before),2,parsed,"backend/lib",8).affectedServices())
                .containsExactly("backend/api","backend/batch");
    }
    @Test void emptyOrUnsafePomDoesNotBecomeASuccessfulModuleDiscovery() {
        assertThat(graph(Map.of("frontend/package.json", "{}")).warnings()).containsExactly("NO_MAVEN_ROOT");
        var parsed=graph(Map.of("backend/pom.xml","<!DOCTYPE project SYSTEM 'file:///etc/passwd'><project/>"));
        assertThat(parsed.modules()).isEmpty();
        var root=graph(Map.of("pom.xml","broken", "backend/pom.xml","<project><artifactId>backend</artifactId></project>"));
        assertThat(root.modules()).isEmpty();
    }
    @Test void deletedDependenciesAreTracedThroughBaseOnly() {
        var newer = files("1"); newer.put("lib/pom.xml",child("lib",""));
        var result = impact.compare(1,graph(files("1")),2,graph(newer),null,8);
        assertThat(result.affectedServices()).containsExactly("api","batch");
        assertThat(result.changes().getFirst().kind()).isEqualTo("REMOVED");
        assertThat(result.impacts()).allSatisfy(i -> assertThat(i.view()).isEqualTo("BASE"));
    }
    @Test void childPropertyOverridePreventsFalsePropagation() {
        var left = files("1"); var right = files("2");
        for (var file : java.util.List.of(left,right)) file.put("lib/pom.xml", file.get("lib/pom.xml").replace("<dependencies>","<properties><library.version>9</library.version></properties><dependencies>"));
        assertThat(impact.compare(1,graph(left),2,graph(right),null,8).changes()).isEmpty();
    }
    @Test void managementWithoutConsumptionDoesNotCreateDependencyEdges() {
        var left=files("1"); var right=files("1");
        left.put("pom.xml",left.get("pom.xml").replace("</project>",management("unused","1")+"</project>"));
        right.put("pom.xml",right.get("pom.xml").replace("</project>",management("unused","2")+"</project>"));
        assertThat(impact.compare(1,graph(left),2,graph(right),null,8).changes()).isEmpty();
    }
    @Test void inheritedManagementResolvesConsumedVersions() {
        var file=files("1");
        file.put("pom.xml",file.get("pom.xml").replace("</project>",management("core","7")+"</project>"));
        file.put("lib/pom.xml",file.get("lib/pom.xml").replace("<version>${library.version}</version>",""));
        assertThat(graph(file).modules().stream().filter(m->m.path().equals("lib")).findFirst().orElseThrow().dependencies().getFirst().version()).isEqualTo("7");
    }
    @Test void testScopeAndOptionalDependencyDoNotLeakToTransitiveConsumers() {
        for (String suffix : java.util.List.of("<scope>test</scope>","<scope>provided</scope>","<optional>true</optional>")) {
            var left=files("1"); var right=files("2");
            for (var file:java.util.List.of(left,right)) file.put("lib/pom.xml",file.get("lib/pom.xml").replace("</dependency>",suffix+"</dependency>"));
            assertThat(impact.compare(1,graph(left),2,graph(right),null,8).affectedServices()).isEmpty();
        }
    }
    @Test void cycleTerminatesWithoutMixingBaseAndHeadEdges() {
        var left=files("1"); var right=files("2");
        for (var file:java.util.List.of(left,right)) file.put("lib/pom.xml",file.get("lib/pom.xml").replace("</dependencies>",
                "<dependency><groupId>fixture</groupId><artifactId>api</artifactId><version>1</version></dependency></dependencies>"));
        var result=impact.compare(1,graph(left),2,graph(right),null,8);
        assertThat(result.affectedServices()).containsExactly("api","batch");
        assertThat(result.warnings()).contains("DEPENDENCY_CYCLE");
        assertThat(result.impacts()).hasSizeLessThan(20);
    }
    @Test void exclusionsStopTheExcludedTransitivePath() {
        var left=files("1");var right=files("2");
        for(var file:java.util.List.of(left,right))file.put("api/pom.xml",file.get("api/pom.xml").replace("</dependency>",
                "<exclusions><exclusion><groupId>external</groupId><artifactId>core</artifactId></exclusion></exclusions></dependency>"));
        assertThat(impact.compare(1,graph(left),2,graph(right),null,8).affectedServices()).containsExactly("batch");
    }
    @Test void unresolvedBomIsReportedRatherThanInventingVersions() {
        var file=files("1");
        file.put("pom.xml",file.get("pom.xml").replace("</project>","<dependencyManagement><dependencies><dependency><groupId>external</groupId><artifactId>bom</artifactId><version>3</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>"));
        file.put("lib/pom.xml",file.get("lib/pom.xml").replace("<version>${library.version}</version>",""));
        var parsed=graph(file);
        assertThat(parsed.warnings()).anyMatch(w->w.startsWith("UNRESOLVED_BOM:"));
        assertThat(parsed.modules().stream().filter(m->m.path().equals("lib")).findFirst().orElseThrow().dependencies().getFirst().resolution()).isEqualTo("UNKNOWN");
    }
    @Test void externalEntitiesAndModuleEscapesAreRejected() {
        assertThatThrownBy(()->PomXml.parse("<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///not-to-be-read'>]><project><artifactId>&xxe;</artifactId></project>"))
                .hasMessage("INVALID_OR_UNSAFE_POM");
        var file=files("1"); file.put("pom.xml",file.get("pom.xml").replace("<module>lib</module>","<module>../../outside</module>"));
        assertThat(graph(file).warnings()).contains("UNRESOLVED_MODULE_PATH:pom.xml");
    }
    private static String management(String artifact,String version) { return "<dependencyManagement><dependencies><dependency><groupId>external</groupId><artifactId>"+artifact+"</artifactId><version>"+version+"</version></dependency></dependencies></dependencyManagement>"; }
}
