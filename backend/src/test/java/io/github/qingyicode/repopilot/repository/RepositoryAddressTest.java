package io.github.qingyicode.repopilot.repository;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RepositoryAddressTest {
    @Test void normalizesGithubCopyFormatsWithoutFetchingArbitraryUrls() {
        for(String value:new String[]{"QINGYICODE/RepoPilot","https://github.com/QINGYICODE/RepoPilot.git/",
                "https://github.com/QINGYICODE/RepoPilot?tab=readme-ov-file","https://github.com/QINGYICODE/RepoPilot/tree/main/backend",
                "git@github.com:QINGYICODE/RepoPilot.git"})
            assertThat(RepositoryAddress.parse(value).fullName()).isEqualTo("qingyicode/repopilot");
    }
    @Test void rejectsCredentialsForeignHostsTraversalAndLocalPaths() {
        for(String value:new String[]{"https://github.com.evil.test/owner/repo","https://secret@github.com/owner/repo","https://github.com:444/owner/repo",
                "file:///tmp/repo","https://github.com/owner/%2e%2e","/tmp/repo","owner/repo/extra","https://127.0.0.1/owner/repo"})
            assertThatThrownBy(()->RepositoryAddress.parse(value)).isInstanceOf(io.github.qingyicode.repopilot.common.DomainException.class);
    }
}
