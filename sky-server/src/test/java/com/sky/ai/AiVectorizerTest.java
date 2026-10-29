package com.sky.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVectorizerTest {

    private final AiVectorizer vectorizer = new AiVectorizer();

    @Test
    void producesNormalizedFixedDimensionVector() {
        float[] vector = vectorizer.vectorize("HashMap 扩容与线程池");
        assertEquals(AiVectorizer.DIM, vector.length);
        double norm = 0.0d;
        for (float value : vector) {
            norm += value * value;
        }
        assertEquals(1.0d, Math.sqrt(norm), 1e-5);
    }

    @Test
    void identicalTextHasCosineOne() {
        float[] left = vectorizer.vectorize("ThreadLocal 内存泄漏与 remove");
        float[] right = vectorizer.vectorize("ThreadLocal 内存泄漏与 remove");
        assertEquals(1.0d, vectorizer.cosine(left, right), 1e-5);
    }

    @Test
    void relatedChineseTextOutranksUnrelatedText() {
        float[] query = vectorizer.vectorize("线程池");
        double related = vectorizer.cosine(query, vectorizer.vectorize("线程池的核心参数与拒绝策略"));
        double unrelated = vectorizer.cosine(query, vectorizer.vectorize("HashMap 的红黑树与扩容"));
        assertTrue(related > 0.3d);
        assertTrue(related > unrelated);
    }

    @Test
    void asciiWordsAreCaseInsensitive() {
        double score = vectorizer.cosine(
                vectorizer.vectorize("ThreadPool Executor"),
                vectorizer.vectorize("threadpool executor"));
        assertEquals(1.0d, score, 1e-5);
    }

    @Test
    void extractsChineseBigramsAndAsciiWords() {
        List<String> terms = AiVectorizer.terms("线程池 JDK 8");
        assertTrue(terms.contains("线程"));
        assertTrue(terms.contains("程池"));
        assertTrue(terms.contains("jdk"));
    }

    @Test
    void emptyTextYieldsZeroVector() {
        float[] vector = vectorizer.vectorize("");
        boolean allZero = true;
        for (float value : vector) {
            if (value != 0.0f) {
                allZero = false;
            }
        }
        assertTrue(allZero);
        assertFalse(vectorizer.cosine(vector, vectorizer.vectorize("线程池")) > 0.0d);
    }
}
