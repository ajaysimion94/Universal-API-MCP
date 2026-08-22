package com.mcpserver.connectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConnectionPollingSchedulerSpringTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void backgroundConnectionPollingIsDisabledInSpringTests() {
        assertThat(applicationContext.getBeansOfType(ConnectionPollingScheduler.class)).isEmpty();
    }
}
