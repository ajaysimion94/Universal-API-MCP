package com.mcpserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Browser-native SPA route forwards. Static HTML/CSS/JS lives directly in
        // resources/static, so Maven and a Java runtime are the only build tools.
        registry.addViewController("/files").setViewName("forward:/index.html");
        registry.addViewController("/files/**").setViewName("forward:/index.html");
        registry.addViewController("/plugins").setViewName("forward:/index.html");
        registry.addViewController("/connections").setViewName("forward:/index.html");
        registry.addViewController("/apps").setViewName("forward:/index.html");
        registry.addViewController("/guide").setViewName("forward:/index.html");
        registry.addViewController("/insights").setViewName("forward:/index.html");
        registry.addViewController("/insights/**").setViewName("forward:/index.html");
        // Kept so links made before the rename still resolve; the SPA redirects them.
        registry.addViewController("/dashboards").setViewName("forward:/index.html");
        registry.addViewController("/dashboards/**").setViewName("forward:/index.html");
        registry.addViewController("/reports").setViewName("forward:/index.html");
    }

}
