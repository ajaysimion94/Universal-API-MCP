package com.mcpserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // React SPA route forwards. Maven overlays the compiled Vite entry point on the static
        // resources; compatibility page controllers continue to load as ordinary ES modules.
        registry.addViewController("/files").setViewName("forward:/index.html");
        registry.addViewController("/files/**").setViewName("forward:/index.html");
        registry.addViewController("/plugins").setViewName("forward:/index.html");
        registry.addViewController("/connections").setViewName("forward:/index.html");
        registry.addViewController("/apps").setViewName("forward:/index.html");
        registry.addViewController("/help").setViewName("forward:/index.html");
        registry.addViewController("/tutorial").setViewName("forward:/index.html");
        // Earlier name for the help page; the client router redirects it to /help.
        registry.addViewController("/guide").setViewName("forward:/index.html");
        registry.addViewController("/insights").setViewName("forward:/index.html");
        registry.addViewController("/insights/**").setViewName("forward:/index.html");
        // Kept so links made before the rename still resolve; the SPA redirects them.
        registry.addViewController("/dashboards").setViewName("forward:/index.html");
        registry.addViewController("/dashboards/**").setViewName("forward:/index.html");
        registry.addViewController("/reports").setViewName("forward:/index.html");
        registry.addViewController("/reports/**").setViewName("forward:/index.html");
    }

}
