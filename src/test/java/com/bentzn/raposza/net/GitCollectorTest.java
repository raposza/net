/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The collector against a repository the test builds, so nothing here reaches
 * the network and nothing depends on what an upstream project happens to hold
 * today.
 *
 * Author Claude/bentzn
 */
class GitCollectorTest {

    @TempDir
    Path dirTmp;


    @Test
    void banksOneObservationPerCommitAndThenGoesQuiet() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirUp = dirTmp.resolve("up");
        Files.createDirectories(dirUp);
        git(dirUp, "init", "-q", "-b", "main", ".");
        git(dirUp, "config", "user.email", "test@example.invalid");
        git(dirUp, "config", "user.name", "test");
        write(dirUp.resolve("configs/DevNet/a.yaml"), "value: 1\n");
        write(dirUp.resolve("a path/with space.txt"), "one\n");
        commit(dirUp, "one");
        write(dirUp.resolve("configs/DevNet/a.yaml"), "value: 2\n");
        commit(dirUp, "two");

        SourceDef def = def(dirUp, null);
        Path dirMirror = dirTmp.resolve("git");
        Path dirEvidence = dirTmp.resolve("evidence");

        Poll resFirst = GitCollector.collect(def, dirMirror, dirEvidence, null);
        assertEquals("CHANGED", resFirst.outcome(), resFirst.detail());
        assertEquals(2, resFirst.lstBanked().size());

        Banked obsHead = resFirst.lstBanked().get(1);
        assertEquals(resFirst.cursor(), obsHead.revision());
        assertTrue(Evidence.has(dirEvidence, obsHead.keyStorage()));
        String textManifest = new String(Evidence.get(dirEvidence, obsHead.keyStorage()), StandardCharsets.UTF_8);
        assertTrue(textManifest.contains("\"path\":\"a path/with space.txt\""), textManifest);
        assertTrue(textManifest.contains("\"path\":\"configs/DevNet/a.yaml\""), textManifest);
        assertNotEquals(resFirst.lstBanked().get(0).shaContent(), obsHead.shaContent());

        Poll resAgain = GitCollector.collect(def, dirMirror, dirEvidence, obsHead.revision());
        assertEquals("UNCHANGED", resAgain.outcome(), resAgain.detail());
        assertTrue(resAgain.lstBanked().isEmpty());

        write(dirUp.resolve("configs/DevNet/a.yaml"), "value: 3\n");
        commit(dirUp, "three");
        Poll resThird = GitCollector.collect(def, dirMirror, dirEvidence, obsHead.revision());
        assertEquals("CHANGED", resThird.outcome(), resThird.detail());
        assertEquals(1, resThird.lstBanked().size());
    }


    @Test
    void tracksOnlyThePrefixItWasGiven() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirUp = dirTmp.resolve("up");
        Files.createDirectories(dirUp);
        git(dirUp, "init", "-q", "-b", "main", ".");
        git(dirUp, "config", "user.email", "test@example.invalid");
        git(dirUp, "config", "user.name", "test");
        write(dirUp.resolve("configs/MainNet/b.yaml"), "value: 1\n");
        write(dirUp.resolve("scripts/run.sh"), "true\n");
        commit(dirUp, "one");

        Poll res = GitCollector.collect(def(dirUp, "configs"),
                dirTmp.resolve("git"), dirTmp.resolve("evidence"), null);
        assertEquals("CHANGED", res.outcome(), res.detail());
        String textManifest = new String(Evidence.get(dirTmp.resolve("evidence"),
                res.lstBanked().get(0).keyStorage()), StandardCharsets.UTF_8);
        assertTrue(textManifest.contains("configs/MainNet/b.yaml"), textManifest);
        assertFalse(textManifest.contains("scripts/run.sh"), textManifest);
    }


    @Test
    void reportsAnUnreachableRemoteRatherThanThrowing() {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        SourceDef def = new SourceDef("nowhere", "test", "git",
                dirTmp.resolve("no-such-repo").toString(), null, null,
                "COMMUNITY", "none", 300, false, true);
        Poll res = GitCollector.collect(def, dirTmp.resolve("git"),
                dirTmp.resolve("evidence"), null);
        assertEquals("TRANSPORT_ERROR", res.outcome());
        assertTrue(res.lstBanked().isEmpty());
    }


    @Test
    void derivesTheSameObservationIdForTheSameState() {
        String idOne = Observations.id("src", "abc123");
        assertEquals(idOne, Observations.id("src", "abc123"));
        assertNotEquals(idOne, Observations.id("src", "abc124"));
        assertNotEquals(idOne, Observations.id("other", "abc123"));
        assertTrue(idOne.startsWith("obs_"));
    }


    @Test
    void carriesEverySourceDefinitionTheArtifactShips() throws Exception {
        List<SourceDef> lstDef = Sources.load();
        assertTrue(lstDef.size() >= 1);
        for (SourceDef def : lstDef) {
            assertTrue(def.id() != null && !def.id().isBlank());
            assertTrue(def.url().startsWith("https://"), def.id());
            assertTrue(def.pollSeconds() > 0, def.id());
            assertTrue(def.sourceAuthority() != null && !def.sourceAuthority().isBlank(), def.id());
        }
    }


    private SourceDef def(Path dirUp, String pathPrefix) {
        return new SourceDef("test-source", "test", "git", dirUp.toString(), null, pathPrefix,
                "OFFICIAL", "none", 300, false, true);
    }


    private void write(Path fileOut, String textBody) throws IOException {
        Files.createDirectories(fileOut.getParent());
        Files.writeString(fileOut, textBody);
    }


    private void commit(Path dirUp, String textMessage) throws Exception {
        git(dirUp, "add", "-A");
        git(dirUp, "commit", "-q", "-m", textMessage);
    }


    private void git(Path dirUp, String... argsGit) throws Exception {
        String[] argsFull = new String[argsGit.length + 1];
        argsFull[0] = "git";
        System.arraycopy(argsGit, 0, argsFull, 1, argsGit.length);
        ProcessBuilder bldProc = new ProcessBuilder(argsFull);
        bldProc.directory(dirUp.toFile());
        bldProc.redirectErrorStream(true);
        bldProc.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        assertEquals(0, bldProc.start().waitFor(), String.join(" ", argsFull));
    }


    private boolean hasGit() {
        try {
            ProcessBuilder bldProc = new ProcessBuilder("git", "--version");
            bldProc.redirectErrorStream(true);
            bldProc.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return bldProc.start().waitFor() == 0;
        }
        catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
