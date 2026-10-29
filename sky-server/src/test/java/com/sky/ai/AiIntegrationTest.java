package com.sky.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiIntegrationTest {

    private static final String TOKEN = "test-token-0123456789abcdef";
    private static final String H2_URL =
            "jdbc:h2:mem:aitest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    private static final String DOC_SOURCE = "test/quantum.md";

    private static final AtomicReference<String> MODEL_MODE = new AtomicReference<String>("stream");
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<String>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<String>();
    private static final HttpServer MODEL_SERVER = startModelServer();

    @SpringBootApplication(scanBasePackages = "com.sky.ai")
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> H2_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.mvc.pathmatch.matching-strategy", () -> "ant_path_matcher");
        registry.add("spring.datasource.druid.url", () -> H2_URL);
        registry.add("spring.datasource.druid.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.druid.username", () -> "sa");
        registry.add("spring.datasource.druid.password", () -> "");
        registry.add("spring.datasource.druid.validation-query", () -> "SELECT 1");
        registry.add("app.ai.access-token", () -> TOKEN);
        registry.add("app.ai.chat-base-url",
                () -> "http://127.0.0.1:" + MODEL_SERVER.getAddress().getPort() + "/v1");
        registry.add("app.ai.chat-api-key", () -> "test-api-key");
        registry.add("app.ai.chat-model", () -> "test-model");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiService aiService;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void stopModelServer() {
        MODEL_SERVER.stop(0);
    }

    @Test
    @Order(1)
    void rejectsMissingOrWrongToken() throws Exception {
        mockMvc.perform(get("/ai/api/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/ai/api/status").header("X-AI-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        MvcResult page = mockMvc.perform(get("/ai/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        String html = new String(page.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(html.contains("苍穹外卖 · Java 知识助手"));
    }

    @Test
    @Order(2)
    void reportsDisabledWhenTokenNotConfigured() throws Exception {
        String token = aiProperties.getAccessToken();
        aiProperties.setAccessToken(" ");
        try {
            mockMvc.perform(get("/ai/api/status"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AI_DISABLED"));
        } finally {
            aiProperties.setAccessToken(token);
        }
    }

    @Test
    @Order(3)
    void statusExposesChunkCountAndChatFlag() throws Exception {
        mockMvc.perform(get("/ai/api/status").header("X-AI-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.chunkCount").isNumber())
                .andExpect(jsonPath("$.chatEnabled").value(true))
                .andExpect(jsonPath("$.retrieval").value(org.hamcrest.Matchers.containsString("term-hash")));
        assertTrue(aiService.status().get("chunkCount") instanceof Integer);
    }

    @Test
    @Order(4)
    void loadsFourSeedDocuments() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai/api/knowledge").header("X-AI-Token", TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = body.path("items");
        assertEquals(4, items.size());
        List<String> sources = new ArrayList<String>();
        for (JsonNode item : items) {
            sources.add(item.path("source").asText());
            assertTrue(item.path("chunkCount").asInt() > 0);
        }
        assertTrue(sources.contains("java-basics.md"));
        assertTrue(sources.contains("java-concurrency.md"));
        assertTrue(sources.contains("spring-boot-web.md"));
        assertTrue(sources.contains("mybatis-and-sql.md"));
        assertTrue(body.path("chunkCount").asInt() >= 30);
    }

    @Test
    @Order(5)
    void importsDocumentAndFindsItFirst() throws Exception {
        String content = "量子咖啡机 QCM-9000 运维手册。设备采用钛合金研磨头，研磨转速为每分钟三千转。"
                + "清洁时应先断电，再取出研磨仓。若出现 E42 报错，请检查奶泡温度传感器。";
        mockMvc.perform(post("/ai/api/knowledge")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(document(content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(DOC_SOURCE))
                .andExpect(jsonPath("$.chunkCount").value(1));

        MvcResult result = mockMvc.perform(post("/ai/api/search")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"量子咖啡机研磨\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode citations = objectMapper.readTree(
                result.getResponse().getContentAsString()).path("citations");
        assertTrue(citations.size() > 0);
        assertEquals(DOC_SOURCE, citations.get(0).path("source").asText());
    }

    @Test
    @Order(6)
    void rejectsDuplicateSource() throws Exception {
        mockMvc.perform(post("/ai/api/knowledge")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(document("重复来源测试"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_EXISTS"));
    }

    @Test
    @Order(7)
    void rejectsOversizedContent() throws Exception {
        StringBuilder content = new StringBuilder(20001);
        for (int i = 0; i < 20001; i++) {
            content.append('a');
        }
        mockMvc.perform(post("/ai/api/knowledge")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(document(content.toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
    }

    @Test
    @Order(8)
    void deletedDocumentIsNoLongerSearchable() throws Exception {
        mockMvc.perform(delete("/ai/api/knowledge")
                        .header("X-AI-Token", TOKEN)
                        .param("source", DOC_SOURCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1));
        MvcResult result = mockMvc.perform(post("/ai/api/search")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"量子咖啡机研磨\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode citations = objectMapper.readTree(
                result.getResponse().getContentAsString()).path("citations");
        for (JsonNode citation : citations) {
            assertFalse(DOC_SOURCE.equals(citation.path("source").asText()));
        }
    }

    @Test
    @Order(9)
    void chatWithoutModelOnlyReturnsRetrieval() throws Exception {
        String apiKey = aiProperties.getChatApiKey();
        aiProperties.setChatApiKey(null);
        try {
            RecordingSink sink = new RecordingSink();
            aiService.chat("HashMap 如何扩容", null, sink);
            assertEquals(Arrays.asList("meta", "notice", "done"), sink.names());
            JsonNode meta = objectMapper.readTree(sink.json("meta"));
            assertFalse(meta.path("chatEnabled").asBoolean());
            assertTrue(meta.path("citations").size() > 0);
            assertTrue(sink.json("notice").contains("未配置生成模型"));
            assertTrue(sink.json("notice").contains("AI_CHAT_BASE_URL"));
            assertFalse(sink.names().contains("delta"));
        } finally {
            aiProperties.setChatApiKey(apiKey);
        }
    }

    @Test
    @Order(10)
    void streamsModelDeltasWithCitationMarker() throws Exception {
        MODEL_MODE.set("stream");
        RecordingSink sink = new RecordingSink();
        aiService.chat("HashMap 如何扩容", null, sink);
        assertEquals("meta", sink.names().get(0));
        assertEquals("done", sink.names().get(sink.names().size() - 1));
        assertFalse(sink.names().contains("error"));
        assertTrue(objectMapper.readTree(sink.json("meta")).path("citations").size() > 0);
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < sink.events.size(); i++) {
            if ("delta".equals(sink.events.get(i)[0])) {
                answer.append(objectMapper.readTree(sink.events.get(i)[1]).path("text").asText());
            }
        }
        assertTrue(answer.toString().contains("[1]"));
        assertNotNull(LAST_AUTH.get());
        assertEquals("Bearer test-api-key", LAST_AUTH.get());
        JsonNode request = objectMapper.readTree(LAST_BODY.get());
        assertTrue(request.path("stream").asBoolean());
        assertEquals(0.2d, request.path("temperature").asDouble(), 1e-9);
        assertTrue(request.path("messages").get(1).path("content").asText().contains("[1]"));
    }

    @Test
    @Order(11)
    void modelFailureEmitsErrorWithoutFabricatedContent() {
        MODEL_MODE.set("error");
        RecordingSink sink = new RecordingSink();
        aiService.chat("HashMap 如何扩容", null, sink);
        assertFalse(sink.names().contains("delta"));
        assertTrue(sink.names().contains("error"));
        assertEquals("done", sink.names().get(sink.names().size() - 1));
        assertTrue(sink.json("error").contains("模型服务暂时不可用"));
    }

    @Test
    @Order(12)
    void validatesChatRequestBeforeStreaming() throws Exception {
        mockMvc.perform(post("/ai/api/chat")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        StringBuilder question = new StringBuilder();
        for (int i = 0; i < 4001; i++) {
            question.append('问');
        }
        mockMvc.perform(post("/ai/api/chat")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUESTION_TOO_LONG"));
        mockMvc.perform(post("/ai/api/search")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"线程池\",\"topK\":11}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(13)
    void urlPolicyAllowsHttpsOrLoopbackOnly() {
        assertTrue(AiService.urlAllowed("https://api.example.com/v1"));
        assertTrue(AiService.urlAllowed("http://localhost:8080/v1"));
        assertTrue(AiService.urlAllowed("http://127.0.0.1:9000"));
        assertFalse(AiService.urlAllowed("http://api.example.com/v1"));
        assertFalse(AiService.urlAllowed("ftp://127.0.0.1/v1"));
        assertFalse(AiService.urlAllowed("not a url"));
    }

    @Test
    @Order(14)
    void streamsSseOverHttp() throws Exception {
        MODEL_MODE.set("stream");
        MvcResult result = mockMvc.perform(post("/ai/api/chat")
                        .header("X-AI-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"HashMap 如何扩容\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(15000L);
        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        String body = new String(dispatched.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(body.contains("event:meta"));
        assertTrue(body.contains("event:delta"));
        assertTrue(body.contains("event:done"));
    }

    private static java.util.Map<String, String> document(String content) {
        java.util.Map<String, String> body = new java.util.LinkedHashMap<String, String>();
        body.put("title", "量子咖啡机运维手册");
        body.put("content", content);
        body.put("source", DOC_SOURCE);
        return body;
    }

    private static HttpServer startModelServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", AiIntegrationTest::handleModel);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动模拟模型服务", e);
        }
    }

    private static void handleModel(HttpExchange exchange) throws IOException {
        LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
        LAST_BODY.set(readBody(exchange.getRequestBody()));
        if ("error".equals(MODEL_MODE.get())) {
            byte[] body = "{\"error\":{\"message\":\"boom\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"线程池可以复用线程\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，从而减少创建开销[1]。\"}}]}\n\n"
                + "data: [DONE]\n\n";
        byte[] body = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class RecordingSink implements AiService.SseSink {
        private final List<String[]> events = new ArrayList<String[]>();

        @Override
        public void event(String name, String json) {
            events.add(new String[]{name, json});
        }

        @Override
        public boolean closed() {
            return false;
        }

        List<String> names() {
            List<String> names = new ArrayList<String>();
            for (String[] event : events) {
                names.add(event[0]);
            }
            return names;
        }

        String json(String name) {
            for (String[] event : events) {
                if (name.equals(event[0])) {
                    return event[1];
                }
            }
            return "";
        }
    }
}
