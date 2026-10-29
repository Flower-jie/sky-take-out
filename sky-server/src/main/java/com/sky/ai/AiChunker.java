package com.sky.ai;

import java.util.ArrayList;
import java.util.List;

public final class AiChunker {

    public static final int CHUNK_SIZE = 800;
    public static final int OVERLAP = 100;

    private AiChunker() {
    }

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<String>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int length = normalized.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            if (end < length && Character.isHighSurrogate(normalized.charAt(end - 1))) {
                end--;
            }
            String piece = strip(normalized.substring(start, end));
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= length) {
                break;
            }
            int next = end - OVERLAP;
            if (next <= start) {
                next = end;
            }
            start = next;
        }
        return chunks;
    }

    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }
}
