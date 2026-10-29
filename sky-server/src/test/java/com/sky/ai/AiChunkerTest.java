package com.sky.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChunkerTest {

    @Test
    void normalizesCrLfAndCr() {
        List<String> chunks = AiChunker.split("第一行\r\n第二行\r第三行");
        assertEquals(1, chunks.size());
        assertEquals("第一行\n第二行\n第三行", chunks.get(0));
    }

    @Test
    void splitsLongTextIntoOverlappingChunks() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            text.append((char) ('a' + (i % 26)));
        }
        List<String> chunks = AiChunker.split(text.toString());
        assertEquals(3, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= AiChunker.CHUNK_SIZE);
        }
        assertEquals(chunks.get(0).substring(700), chunks.get(1).substring(0, 100));
        assertEquals(chunks.get(1).substring(700), chunks.get(2).substring(0, 100));
    }

    @Test
    void neverSplitsSurrogatePair() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 799; i++) {
            text.append('a');
        }
        String emoji = "\uD83D\uDE00";
        text.append(emoji);
        for (int i = 0; i < 1200; i++) {
            text.append('b');
        }
        List<String> chunks = AiChunker.split(text.toString());
        boolean found = false;
        for (String chunk : chunks) {
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            if (chunk.contains(emoji)) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void skipsBlankChunks() {
        assertTrue(AiChunker.split(null).isEmpty());
        assertTrue(AiChunker.split("").isEmpty());
        assertTrue(AiChunker.split("  \r\n\t\r\n  ").isEmpty());
    }

    @Test
    void keepsShortTextAsSingleChunk() {
        List<String> chunks = AiChunker.split("  HashMap 扩容  ");
        assertEquals(1, chunks.size());
        assertEquals("HashMap 扩容", chunks.get(0));
    }
}
