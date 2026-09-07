/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evidence store, against a temporary directory. No database, no network.
 *
 * Author Claude/bentzn
 */
class EvidenceTest {

    private static final byte[] BYTES_A = "one body".getBytes(StandardCharsets.UTF_8);

    private static final byte[] BYTES_B = "another body".getBytes(StandardCharsets.UTF_8);


    @Test
    void keyIsTheSha256OfTheContent() {
        String keyA = Evidence.key(BYTES_A);
        assertTrue(keyA.startsWith("sha256/"), "the key carries the algorithm: " + keyA);
        assertEquals(71, keyA.length(), "sha256/ plus 64 hex characters");
        assertEquals(keyA, Evidence.key(BYTES_A), "the same bytes address the same key");
        assertNotEquals(keyA, Evidence.key(BYTES_B), "different bytes address different keys");
    }


    @Test
    void putIsIdempotentAndReadsBack(@TempDir Path dirRoot) throws IOException {
        String keyFirst = Evidence.put(dirRoot, BYTES_A);
        String keySecond = Evidence.put(dirRoot, BYTES_A);
        assertEquals(keyFirst, keySecond, "storing twice yields one key");
        assertTrue(Evidence.has(dirRoot, keyFirst));
        assertArrayEquals(BYTES_A, Evidence.get(dirRoot, keyFirst));
        assertEquals(1, files(dirRoot).size(), "storing twice writes one file");
    }


    @Test
    void aChangedBodyIsANewFile(@TempDir Path dirRoot) throws IOException {
        Evidence.put(dirRoot, BYTES_A);
        Evidence.put(dirRoot, BYTES_B);
        assertEquals(2, files(dirRoot).size(), "two bodies, two files");
    }


    @Test
    void noTemporaryFileSurvives(@TempDir Path dirRoot) throws IOException {
        Evidence.put(dirRoot, BYTES_A);
        assertTrue(files(dirRoot).stream().noneMatch(fileFound -> fileFound.endsWith(".tmp")),
                "the temporary file is moved, not left behind");
    }


    @Test
    void absentBodyIsReportedAbsent(@TempDir Path dirRoot) {
        assertFalse(Evidence.has(dirRoot, Evidence.key(BYTES_B)));
    }


    private static List<String> files(Path dirRoot) throws IOException {
        try (Stream<Path> strmPath = Files.walk(dirRoot)) {
            return strmPath.filter(Files::isRegularFile).map(Path::toString).toList();
        }
    }
}
