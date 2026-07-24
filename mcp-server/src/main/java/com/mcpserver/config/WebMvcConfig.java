package com.mcpserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA route forwards. Keep these explicit so hashed Vite assets under /assets
        // are served as static files instead of being forwarded to index.html.
        registry.addViewController("/files").setViewName("forward:/index.html");
        registry.addViewController("/files/**").setViewName("forward:/index.html");
        registry.addViewController("/plugins").setViewName("forward:/index.html");
        registry.addViewController("/connections").setViewName("forward:/index.html");
        registry.addViewController("/apps").setViewName("forward:/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Phase 1 only: trusted internal network, no auth yet (guardrail from plan.md §1).
        // Allow the Vite dev server to call the API during development.
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
