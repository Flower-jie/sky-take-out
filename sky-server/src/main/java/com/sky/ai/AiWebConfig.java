package com.sky.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目使用 WebMvcConfigurationSupport，Boot 默认静态资源映射不会生效，这里只补 /ai 页面的三个文件。
 */
@Configuration
public class AiWebConfig {

    @Bean
    public ResourceHttpRequestHandler aiStaticResourceHandler() {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(Collections.singletonList(new ClassPathResource("static/")));
        return handler;
    }

    @Bean
    public SimpleUrlHandlerMapping aiStaticMapping(ResourceHttpRequestHandler aiStaticResourceHandler) {
        Map<String, Object> urlMap = new LinkedHashMap<String, Object>();
        ParameterizableViewController index = new ParameterizableViewController();
        index.setViewName("redirect:/ai/index.html");
        urlMap.put("/ai", index);
        urlMap.put("/ai/", index);
        urlMap.put("/ai/index.html", aiStaticResourceHandler);
        urlMap.put("/ai/ai.css", aiStaticResourceHandler);
        urlMap.put("/ai/ai.js", aiStaticResourceHandler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        mapping.setUrlMap(urlMap);
        return mapping;
    }
}
