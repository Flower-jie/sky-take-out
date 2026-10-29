package com.sky.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/ai/api")
public class AiController {

    private static final long SSE_TIMEOUT = 90000L;

    private final AiService aiService;
    private final AiStore aiStore;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AiController(AiService aiService, AiStore aiStore, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.aiStore = aiStore;
        this.objectMapper = objectMapper;
        final AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ai-chat-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return aiService.status();
    }

    @GetMapping("/knowledge")
    public Map<String, Object> knowledge() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", aiStore.listSources());
        result.put("chunkCount", aiStore.count());
        return result;
    }

    @PostMapping("/knowledge")
    public Map<String, Object> addKnowledge(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new AiStore.AiException(400, "INVALID_ARGUMENT", "请求体不能为空");
        }
        return aiStore.add(text(body.get("title")), text(body.get("source")), text(body.get("content")));
    }

    @DeleteMapping("/knowledge")
    public Map<String, Object> deleteKnowledge(@RequestParam(value = "source", required = false) String source) {
        int removed = aiStore.delete(source);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed", removed);
        return result;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new AiStore.AiException(400, "INVALID_ARGUMENT", "请求体不能为空");
        }
        return aiService.search(text(body.get("question")), integer(body.get("topK")));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) Map<String, Object> body) {
        final String question = aiService.normalizeQuestion(body == null ? null : text(body.get("question")));
        final int topK = aiService.normalizeTopK(body == null ? null : integer(body.get("topK")));

        final SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        final AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });

        final AiService.SseSink sink = new AiService.SseSink() {
            @Override
            public void event(String name, String json) {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name(name).data(json, MediaType.TEXT_PLAIN));
                } catch (Exception e) {
                    closed.set(true);
                }
            }

            @Override
            public boolean closed() {
                return closed.get();
            }
        };

        executor.execute(() -> {
            try {
                aiService.chat(question, topK, sink);
            } catch (RuntimeException e) {
                sink.event("error", errorJson("AI 服务暂时不可用，请稍后重试"));
            } finally {
                closed.set(true);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 客户端已断开或已超时时 completion 可能重复
                }
            }
        });
        return emitter;
    }

    @ExceptionHandler(AiStore.AiException.class)
    public ResponseEntity<Map<String, Object>> handleAiException(AiStore.AiException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getStatus());
        return ResponseEntity.status(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status).body(body);
    }

    private String errorJson(String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", message);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"message\":\"AI 服务暂时不可用，请稍后重试\"}";
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.valueOf(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new AiStore.AiException(400, "INVALID_ARGUMENT", "topK 必须是整数");
            }
        }
        return null;
    }
}
