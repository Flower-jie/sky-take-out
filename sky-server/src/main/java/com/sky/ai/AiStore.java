package com.sky.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class AiStore {

    private static final Logger log = LoggerFactory.getLogger(AiStore.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS ai_chunk ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "source VARCHAR(160) NOT NULL, "
            + "title VARCHAR(200) NOT NULL, "
            + "ordinal INT NOT NULL, "
            + "content VARCHAR(2000) NOT NULL, "
            + "vector VARCHAR(4000) NOT NULL)";

    private static final int MAX_TITLE = 100;
    private static final int MAX_CONTENT = 20000;
    private static final int MAX_SOURCE = 160;
    private static final int MAX_CHUNKS_PER_DOC = 30;
    private static final int MAX_CHUNKS_TOTAL = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final AiVectorizer vectorizer;

    private volatile boolean ready;

    public AiStore(JdbcTemplate jdbcTemplate, AiVectorizer vectorizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorizer = vectorizer;
    }

    public synchronized void ensure() {
        if (ready) {
            return;
        }
        List<String> seeded = new ArrayList<String>();
        try {
            jdbcTemplate.execute(DDL);
            if (countChunks() == 0) {
                loadSeeds(seeded);
            }
            ready = true;
        } catch (Exception e) {
            log.warn("AI 知识库初始化失败: {}", e.toString());
            for (String source : seeded) {
                try {
                    jdbcTemplate.update("DELETE FROM ai_chunk WHERE source = ?", source);
                } catch (Exception ignored) {
                    // 初始化已失败，清理只是尽力而为
                }
            }
            throw new AiException(503, "AI_UNAVAILABLE", "知识库暂时不可用，请稍后重试");
        }
    }

    private void loadSeeds(List<String> seeded) throws Exception {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:ai/*.md");
        Arrays.sort(resources, new Comparator<Resource>() {
            @Override
            public int compare(Resource left, Resource right) {
                return String.valueOf(left.getFilename()).compareTo(String.valueOf(right.getFilename()));
            }
        });
        for (Resource resource : resources) {
            String source = resource.getFilename();
            if (source == null) {
                continue;
            }
            String text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            String title = seedTitle(text, source);
            List<String> chunks = AiChunker.split(text);
            insert(title, source, chunks);
            seeded.add(source);
        }
    }

    private static String seedTitle(String text, String fallback) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                if (title.length() > MAX_TITLE) {
                    title = title.substring(0, MAX_TITLE);
                }
                return title;
            }
        }
        return fallback;
    }

    public int count() {
        ensure();
        return countChunks();
    }

    private int countChunks() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> listSources() {
        ensure();
        return jdbcTemplate.query(
                "SELECT source, title, COUNT(*) AS chunk_count FROM ai_chunk "
                        + "GROUP BY source, title ORDER BY MIN(id)",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("source", rs.getString("source"));
                    item.put("title", rs.getString("title"));
                    item.put("chunkCount", rs.getInt("chunk_count"));
                    return item;
                });
    }

    public Map<String, Object> add(String title, String source, String content) {
        ensure();
        String docTitle = title == null ? "" : title.trim();
        String docContent = content == null ? "" : content.trim();
        if (docTitle.isEmpty()) {
            throw new AiException(400, "INVALID_ARGUMENT", "标题不能为空");
        }
        if (docTitle.length() > MAX_TITLE) {
            throw new AiException(422, "TITLE_TOO_LONG", "标题不能超过 100 字");
        }
        if (docContent.isEmpty()) {
            throw new AiException(400, "INVALID_ARGUMENT", "内容不能为空");
        }
        if (docContent.length() > MAX_CONTENT) {
            throw new AiException(422, "CONTENT_TOO_LONG", "内容不能超过 20000 字");
        }
        String docSource = source == null ? "" : source.trim();
        if (docSource.isEmpty()) {
            docSource = "manual/" + docTitle;
        }
        if (docSource.length() > MAX_SOURCE) {
            throw new AiException(422, "SOURCE_TOO_LONG", "来源不能超过 160 字");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_chunk WHERE source = ?", Integer.class, docSource);
        if (exists != null && exists > 0) {
            throw new AiException(409, "SOURCE_EXISTS", "该来源已存在，请先删除或换一个来源");
        }
        List<String> chunks = AiChunker.split(docContent);
        if (chunks.size() > MAX_CHUNKS_PER_DOC) {
            throw new AiException(422, "TOO_MANY_CHUNKS", "单个文档最多 30 个片段，请拆分后再导入");
        }
        if (countChunks() + chunks.size() > MAX_CHUNKS_TOTAL) {
            throw new AiException(422, "STORE_FULL", "知识库总量已达上限（5000 片段）");
        }
        insert(docTitle, docSource, chunks);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source", docSource);
        result.put("title", docTitle);
        result.put("chunkCount", chunks.size());
        return result;
    }

    private void insert(String title, String source, List<String> chunks) {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            float[] vector = vectorizer.vectorize(content);
            rows.add(new Object[]{source, title, i + 1, content, formatVector(vector)});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO ai_chunk (source, title, ordinal, content, vector) VALUES (?, ?, ?, ?, ?)",
                rows);
    }

    public int delete(String source) {
        ensure();
        String docSource = source == null ? "" : source.trim();
        if (docSource.isEmpty()) {
            throw new AiException(400, "INVALID_ARGUMENT", "来源不能为空");
        }
        int removed = jdbcTemplate.update("DELETE FROM ai_chunk WHERE source = ?", docSource);
        if (removed == 0) {
            throw new AiException(404, "NOT_FOUND", "未找到该资料来源");
        }
        return removed;
    }

    public List<Hit> search(String query, int topK) {
        ensure();
        final float[] queryVector = vectorizer.vectorize(query);
        final List<Hit> hits = new ArrayList<Hit>();
        jdbcTemplate.query("SELECT source, title, ordinal, content, vector FROM ai_chunk", rs -> {
            double score = vectorizer.cosine(queryVector, parseVector(rs.getString("vector")));
            if (score > 0.0d) {
                hits.add(new Hit(rs.getString("source"), rs.getString("title"),
                        rs.getInt("ordinal"), rs.getString("content"), score));
            }
        });
        hits.sort(new Comparator<Hit>() {
            @Override
            public int compare(Hit left, Hit right) {
                return Double.compare(right.getScore(), left.getScore());
            }
        });
        if (hits.size() > topK) {
            return new ArrayList<Hit>(hits.subList(0, topK));
        }
        return hits;
    }

    private static String formatVector(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.5f", vector[i]));
        }
        return builder.toString();
    }

    private static float[] parseVector(String raw) {
        float[] vector = new float[AiVectorizer.DIM];
        if (raw == null || raw.isEmpty()) {
            return vector;
        }
        String[] parts = raw.split(",");
        int size = Math.min(parts.length, AiVectorizer.DIM);
        for (int i = 0; i < size; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                vector[i] = 0.0f;
            }
        }
        return vector;
    }

    public static class Hit {
        private final String source;
        private final String title;
        private final int ordinal;
        private final String content;
        private final double score;

        public Hit(String source, String title, int ordinal, String content, double score) {
            this.source = source;
            this.title = title;
            this.ordinal = ordinal;
            this.content = content;
            this.score = score;
        }

        public String getSource() {
            return source;
        }

        public String getTitle() {
            return title;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public String getContent() {
            return content;
        }

        public double getScore() {
            return score;
        }
    }

    public static class AiException extends RuntimeException {
        private final int status;
        private final String code;

        public AiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int getStatus() {
            return status;
        }

        public String getCode() {
            return code;
        }
    }
}
