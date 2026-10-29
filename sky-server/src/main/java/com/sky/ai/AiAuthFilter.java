package com.sky.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/ai/api/";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public AiAuthFilter(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith(PREFIX);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = properties.getAccessToken();
        if (expected == null || expected.trim().isEmpty()) {
            reject(response, 503, "AI_DISABLED", "AI 功能未启用，请先配置访问令牌");
            return;
        }
        String given = request.getHeader("X-AI-Token");
        byte[] expectedBytes = expected.trim().getBytes(StandardCharsets.UTF_8);
        byte[] givenBytes = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, givenBytes)) {
            reject(response, 401, "UNAUTHORIZED", "访问令牌无效");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
