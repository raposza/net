/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The publication channel against a repository the test creates, so nothing
 * here reaches the network and no credential is needed. What this does NOT
 * cover is authentication against the real remote: the first push from the host
 * is the first test of the deploy key.
 *
 * Author Claude/bentzn
 */
class GitPublishTest {

    @TempDir
    Path dirTmp;


    @Test
    void commitsOnceAndThenOnlyWhenTheDatasetMoves() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_ARTEFACT).contains("0.8.1"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_README).contains("SCHEDULED, NOT RUNNING"));

        pub.turn(ds("pub_2", "2026-09-18T06:01:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"), "a new publication id alone is not a change");

        pub.turn(ds("pub_3", "2026-09-18T06:02:00Z", "0.8.2"));
        assertEquals(2, commits(dirBare, "main"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_ARTEFACT).contains("pub_3"),
                "the committed file carries the real publication id");
        assertFalse(hasBranch(dirBare, "heartbeat"), "the heartbeat is disabled in this run");
    }


    @Test
    void beatsOnItsOwnBranchAndLeavesTheDataBranchAlone() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, GitPublish.SEC_BEAT_FLOOR));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"));
        assertEquals(1, commits(dirBare, "heartbeat"));
        assertTrue(show(dirBare, "heartbeat", GitPublish.NAME_BEAT).contains("checkedAt"));
        assertFalse(show(dirBare, "heartbeat", GitPublish.NAME_BEAT).contains("networks"),
                "the heartbeat branch carries no data");

        pub.turn(ds("pub_2", "2026-09-18T06:00:30Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "heartbeat"), "the interval has not elapsed");
        assertEquals(1, commits(dirBare, "main"));
    }


    @Test
    void refusesAnIntervalBelowTheFloor() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, GitPublish.SEC_BEAT_FLOOR - 1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"));
        assertFalse(hasBranch(dirBare, "heartbeat"), "an interval below the floor disables the heartbeat");
    }


    @Test
    void namesTagsByDayAndSuffixesOnlyWhenTheDayIsTaken() {
        Instant instDay = Instant.parse("2026-09-18T07:41:40Z");
        assertEquals("v2026-09-18", GitPublish.nextTag(instDay, List.of()));
        assertEquals("v2026-09-18.2", GitPublish.nextTag(instDay, List.of("v2026-09-18")));
        assertEquals("v2026-09-18.3", GitPublish.nextTag(instDay, List.of("v2026-09-18", "v2026-09-18.2")));
        assertEquals("v2026-09-18", GitPublish.nextTag(instDay, List.of("v2026-09-17", "Test-release")));
    }


    @Test
    void readsOwnerAndRepositoryFromEitherRemoteForm() {
        assertEquals("raposza/net_data", GitPublish.slug("git@github.com:raposza/net_data.git"));
        assertEquals("raposza/net_data_dev", GitPublish.slug("https://github.com/raposza/net_data_dev.git"));
        assertEquals("raposza/net_data", GitPublish.slug("ssh://git@github.com/raposza/net_data"));
        assertNull(GitPublish.slug("git@git.example.invalid:raposza/net_data.git"));
        assertNull(GitPublish.slug(null));
    }


    @Test
    void cutsNoReleaseWithoutACredential() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Assumptions.assumeTrue(System.getenv("FEED_GITHUB_TOKEN") == null, "a credential is set in this shell");
        GitPublish pub = GitPublish.of(spec(bare(), -1));
        assertFalse(pub.release("v2026-09-18", "MainNet: Splice 0.8.2 scheduled 2026-09-22", ""));
    }


    private GitPublish.Spec spec(Path dirBare, int secBeat) {
        return new GitPublish.Spec(dirBare.toUri().toString(), "main", "heartbeat",
                dirTmp.resolve("work"), null, null, 0, secBeat, "dev");
    }


    private Path bare() throws Exception {
        Path dirBare = Files.createDirectories(dirTmp.resolve("remote.git"));
        git(dirBare, "init", "-q", "--bare", ".");
        return dirBare;
    }


    private static Map<String, Object> ds(String idPublication, String stampCreated, String verSplice) {
        return Dataset.map(
                "metadata", Dataset.map("publicationId", idPublication, "createdAt", stampCreated,
                        "content", "PLACEHOLDER"),
                "networks", List.of(Dataset.map(
                        "network", "DEVNET",
                        "splice", Dataset.map("scheduledVersion", verSplice),
                        "publicationId", idPublication,
                        "updatedAt", stampCreated)));
    }


    private static int commits(Path dirBare, String nameBranch) throws Exception {
        if (!hasBranch(dirBare, nameBranch))
            return 0;
        return Integer.parseInt(git(dirBare, "rev-list", "--count", nameBranch).trim());
    }


    private static boolean hasBranch(Path dirBare, String nameBranch) throws Exception {
        ProcessBuilder bldProc = new ProcessBuilder("git", "rev-parse", "--verify", "-q",
                "refs/heads/" + nameBranch);
        bldProc.directory(dirBare.toFile());
        bldProc.redirectErrorStream(true);
        Process proc = bldProc.start();
        proc.getInputStream().readAllBytes();
        return proc.waitFor() == 0;
    }


    private static String show(Path dirBare, String nameBranch, String namePath) throws Exception {
        return git(dirBare, "show", nameBranch + ":" + namePath);
    }


    private static String git(Path dirWork, String... argsGit) throws Exception {
        String[] argsFull = new String[argsGit.length + 1];
        argsFull[0] = "git";
        System.arraycopy(argsGit, 0, argsFull, 1, argsGit.length);
        ProcessBuilder bldProc = new ProcessBuilder(argsFull);
        bldProc.directory(dirWork.toFile());
        bldProc.redirectErrorStream(true);
        Process proc = bldProc.start();
        byte[] bytesOut = proc.getInputStream().readAllBytes();
        int codeExit = proc.waitFor();
        String textOut = new String(bytesOut, StandardCharsets.UTF_8);
        if (codeExit != 0)
            throw new IOException(String.join(" ", argsFull) + " exited " + codeExit + ": " + textOut);
        return textOut;
    }


    private static boolean hasGit() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        }
        catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
