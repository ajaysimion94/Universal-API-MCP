package com.mcpserver.connectors;

import com.mcpserver.tools.ApiToolDefinition;
import com.mcpserver.tools.GitHubToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class GitHubConnectorTests {

    @Autowired
    private GitHubConnector gitHubConnector;

    @Autowired
    private GitHubToolProvider gitHubToolProvider;

    @Test
    void testDetectDeployment() throws Exception {
        Connection cloudConnection = Connection.create(
                ConnectionType.GITHUB,
                "GitHub Cloud",
                "https://api.github.com",
                AuthMode.BEARER,
                null,
                "token",
                List.of()
        );

        Connection entConnection = Connection.create(
                ConnectionType.GITHUB,
                "GitHub Enterprise",
                "https://github.mycompany.com/api/v3",
                AuthMode.BEARER,
                null,
                "token",
                List.of()
        );

        assertThat(gitHubConnector.detectDeployment(cloudConnection)).isEqualTo(DeploymentType.CLOUD);
        assertThat(gitHubConnector.detectDeployment(entConnection)).isEqualTo(DeploymentType.SERVER_DC);
    }

    @Test
    void testToolDefinitions() {
        List<ApiToolDefinition> defs = gitHubToolProvider.getDefinitions();
        assertThat(defs).isNotEmpty();
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("list_repositories"));
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("create_issue"));
    }
}
