package com.sky.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量词面哈希向量：中文 bigram + ASCII 单词，FNV-1a 映射到 256 维。它是词面近似匹配，
 * 不是语义 embedding，同义词、跨语言改写无法召回。
 */
@Component
public class AiVectorizer {

    public static final int DIM = 256;

    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    public float[] vectorize(String text) {
        float[] vector = new float[DIM];
        List<String> terms = terms(text);
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            int hash = fnv1a(term);
            int index = (hash & 0x7fffffff) % DIM;
            int sign = ((hash >>> 8) & 1) == 0 ? 1 : -1;
            vector[index] += sign;
        }
        double norm = 0.0d;
        for (int i = 0; i < DIM; i++) {
            norm += vector[i] * vector[i];
        }
        if (norm > 0.0d) {
            float scale = (float) (1.0d / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    public static List<String> terms(String text) {
        List<String> terms = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return terms;
        }
        StringBuilder han = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(word, terms);
                han.appendCodePoint(codePoint);
            } else if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                flushHan(han, terms);
                word.append((char) codePoint);
            } else {
                flushHan(han, terms);
                flushWord(word, terms);
            }
        }
        flushHan(han, terms);
        flushWord(word, terms);
        return terms;
    }

    private static void flushHan(StringBuilder han, List<String> terms) {
        if (han.length() == 0) {
            return;
        }
        if (han.length() == 1) {
            terms.add(han.toString());
        } else {
            for (int i = 0; i + 1 < han.length(); i++) {
                terms.add(han.substring(i, i + 2));
            }
        }
        han.setLength(0);
    }

    private static void flushWord(StringBuilder word, List<String> terms) {
        if (word.length() == 0) {
            return;
        }
        terms.add(word.toString().toLowerCase(Locale.ROOT));
        word.setLength(0);
    }

    private static int fnv1a(String term) {
        int hash = FNV_OFFSET;
        for (int i = 0; i < term.length(); i++) {
            hash ^= term.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    public double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != DIM || right.length != DIM) {
            return 0.0d;
        }
        double dot = 0.0d;
        for (int i = 0; i < DIM; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }
}
