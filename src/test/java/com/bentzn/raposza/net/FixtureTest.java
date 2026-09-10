/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The fixture corpus: real banked bodies under fixtures/, one directory per
 * source, each body named by its sha256 and paired with the claims its
 * normalizer must produce. Every body is read and diffed against its committed
 * expectation, and an unexplained difference is a failure.
 *
 * When a normalizer changes on purpose - a new version - the expectations are
 * rewritten with FEED_FIXTURES_BLESS=1 and the difference is reviewed before it
 * is committed. Nothing else writes them.
 *
 * Author Claude/bentzn
 */
class FixtureTest {

    static final Path DIR_FIXTURES = Path.of("fixtures");

    private static final String SUFFIX_BODY = ".json";

    private static final String SUFFIX_CLAIMS = ".claims.jsonl";


    @Test
    void everyFixtureGivesItsCommittedClaims() throws Exception {
        boolean isBless = "1".equals(System.getenv("FEED_FIXTURES_BLESS"));
        List<Path> lstDir;
        try (Stream<Path> strm = Files.list(DIR_FIXTURES)) {
            lstDir = strm.filter(Files::isDirectory).sorted().toList();
        }
        assertFalse(lstDir.isEmpty(), "no fixture corpus under " + DIR_FIXTURES.toAbsolutePath());
        int cntBody = 0;
        for (Path dirSource : lstDir) {
            String idSource = dirSource.getFileName().toString();
            Normalizer norm = Normalize.forSource(idSource);
            assertNotNull(norm, "fixtures for " + idSource + " but no normalizer reads that source");
            for (Path fileBody : bodies(idSource)) {
                String nameBody = fileBody.getFileName().toString();
                String stem = nameBody.substring(0, nameBody.length() - SUFFIX_BODY.length());
                byte[] bytesBody = Files.readAllBytes(fileBody);
                assertEquals("sha256/" + stem, Evidence.key(bytesBody), nameBody + " is not the body its name addresses");
                String textGot = Claim.lines(norm.normalize(bytesBody));
                Path fileExpect = fileBody.resolveSibling(stem + SUFFIX_CLAIMS);
                if (isBless) {
                    Files.writeString(fileExpect, textGot, StandardCharsets.UTF_8);
                }
                else {
                    assertTrue(Files.isRegularFile(fileExpect), "no expectation " + fileExpect);
                    String textWant = Files.readString(fileExpect, StandardCharsets.UTF_8);
                    if (!textWant.equals(textGot)) {
                        fail(idSource + "/" + nameBody + ": " + firstDifference(textWant, textGot));
                    }
                }
                cntBody++;
            }
        }
        assertTrue(cntBody > 0, "the fixture corpus holds no body");
    }


    @Test
    void scheduleBodiesInAnotherOrderGiveTheSameClaims() throws Exception {
        List<Path> lstBody = bodies(ScheduleNormalizer.SOURCE_ID);
        assertTrue(lstBody.size() >= 2, "two banked schedule bodies are needed to test order");
        Normalizer norm = new ScheduleNormalizer();
        String textFirst = Claim.lines(norm.normalize(Files.readAllBytes(lstBody.get(0))));
        for (Path fileBody : lstBody) {
            assertEquals(textFirst, Claim.lines(norm.normalize(Files.readAllBytes(fileBody))),
                    fileBody.getFileName() + " holds the same records in another order and must give the same claims");
        }
    }


    @Test
    void aTruncatedScheduleBodyIsRefusedNotHalfRead() throws Exception {
        byte[] bytesBody = Files.readAllBytes(bodies(ScheduleNormalizer.SOURCE_ID).get(0));
        byte[] bytesCut = Arrays.copyOf(bytesBody, bytesBody.length / 2);
        assertThrows(Normalizer.Refused.class, () -> new ScheduleNormalizer().normalize(bytesCut));
    }


    /**
     * @param idSource a source id
     * @return the banked bodies under fixtures/ for it, in name order
     * @throws IOException when the directory cannot be listed
     */
    static List<Path> bodies(String idSource) throws IOException {
        try (Stream<Path> strm = Files.list(DIR_FIXTURES.resolve(idSource))) {
            return strm.filter(fileOne -> fileOne.getFileName().toString().endsWith(SUFFIX_BODY)).sorted().toList();
        }
    }


    private static String firstDifference(String textWant, String textGot) {
        String[] arrWant = textWant.split("\n", -1);
        String[] arrGot = textGot.split("\n", -1);
        int cntLine = Math.min(arrWant.length, arrGot.length);
        for (int posLine = 0; posLine < cntLine; posLine++) {
            if (!arrWant[posLine].equals(arrGot[posLine])) {
                return "line " + (posLine + 1) + "\n  want " + clip(arrWant[posLine]) + "\n  got  "
                        + clip(arrGot[posLine]);
            }
        }
        return "want " + arrWant.length + " lines, got " + arrGot.length;
    }


    private static String clip(String textIn) {
        return textIn.length() <= 300 ? textIn : textIn.substring(0, 300) + "...";
    }
}
