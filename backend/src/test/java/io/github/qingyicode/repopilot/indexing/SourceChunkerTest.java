package io.github.qingyicode.repopilot.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceChunkerTest {
    @Test void javaSymbolsRetainAnnotationsAndActualLineRanges() {
        String body="package example;\nclass Demo {\n  @Deprecated\n  void fail() {\n    throw new IllegalStateException(\"ALERT_TEST\");\n  }\n}\n";
        var parsed=new SourceChunker().chunk("Demo.java",body);
        assertThat(parsed.warnings()).isEmpty();
        assertThat(parsed.chunks()).anySatisfy(chunk->{
            assertThat(chunk.symbol()).contains("Demo#fail()");
            assertThat(EvidenceFactory.range(body,chunk.startLine(),chunk.endLine())).contains("@Deprecated","ALERT_TEST");
        });
    }
    @Test void yamlRoutesKeepTheirParentConfiguration() {
        String body="spring:\n  cloud:\n    gateway:\n      routes:\n        - id: api\n          uri: lb://customers\n";
        var parsed=new SourceChunker().chunk("application.yml",body);
        assertThat(parsed.chunks()).hasSize(1);
        assertThat(EvidenceFactory.range(body,parsed.chunks().getFirst().startLine(),parsed.chunks().getFirst().endLine())).contains("spring:","lb://customers");
    }
    @Test void redactionPreservesLineNumbersAndEnvironmentReferences() {
        String source="api_key: \"sk-"+"x".repeat(30)+"\"\npassword: \"very-secret-value\"\npassword: \"${DB_PASSWORD}\"\n-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\n";
        String safe=Redactor.clean(source);
        assertThat(safe).doesNotContain("very-secret-value","secret\n","sk-"+"x".repeat(30)).contains("${DB_PASSWORD}");
        assertThat(safe.split("\n",-1)).hasSameSizeAs(source.split("\n",-1));
    }
    @Test void filesystemEscapeAndAbbreviatedShasNeverReachGit() {
        for (String path:java.util.List.of("../pom.xml","/etc/passwd","a/../../secret","a\\b",".git/config")) {
            assertThatThrownBy(()->RepositoryPolicy.path(path)).isInstanceOf(io.github.qingyicode.repopilot.common.DomainException.class);
        }
        assertThatThrownBy(()->RepositoryPolicy.sha("3858f9c")).hasMessage("FULL_COMMIT_SHA_REQUIRED");
    }
}
