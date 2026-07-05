package com.mcpserver.repositories;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RepositoryConfig {

    @Bean
    public InMemoryFileRepository inMemoryFileRepository() {
        return new InMemoryFileRepository();
    }
}
