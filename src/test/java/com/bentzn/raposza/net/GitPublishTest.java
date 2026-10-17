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
import java.util.ArrayList;
import java.util.Collections;
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
    void commitsOnceAndThenOnlyWhenTheVersionsMove() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_VERSIONS).contains("splice-latest: \"0.8.1\""));
        assertTrue(show(dirBare, "main", GitPublish.NAME_CONTRACT).contains("RAPOSZA TIMESTAMP"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_README).contains("(" + GitPublish.NAME_CONTRACT + ")"));

        pub.turn(ds("pub_2", "2026-09-18T06:01:00Z", "0.8.1"));
        assertEquals(1, commits(dirBare, "main"), "a new timestamp alone is not a change");

        pub.turn(ds("pub_3", "2026-09-18T06:02:00Z", "0.8.2"));
        assertEquals(2, commits(dirBare, "main"));
        assertTrue(show(dirBare, "main", GitPublish.NAME_VERSIONS).contains("timestamp: 2026-09-18T06:02:00Z"),
                "the committed file carries the real timestamp");
        assertFalse(hasBranch(dirBare, "heartbeat"), "the heartbeat is disabled in this run");
    }


    @Test
    void keepsTheCommitLogAndDoesNotRewriteIt() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        String shaRoot = git(dirBare, "rev-parse", "main").trim();

        pub.turn(ds("pub_2", "2026-09-18T06:02:00Z", "0.8.2"));
        assertEquals(2, commits(dirBare, "main"));
        assertEquals(shaRoot, git(dirBare, "rev-parse", "main~1").trim(),
                "the first commit is still the first commit; nothing was amended");
    }


    @Test
    void theReadmeShowsTheDataWithTheContractLinkedOnTop() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        String textReadme = show(dirBare, "main", GitPublish.NAME_README);
        int posLink = textReadme.indexOf("[" + GitPublish.NAME_CONTRACT + "](" + GitPublish.NAME_CONTRACT + ")");
        assertTrue(posLink > 0, textReadme);
        assertTrue(posLink < textReadme.indexOf("## Latest Splice release"), "the link is above the data");
        assertTrue(textReadme.indexOf("## Latest Splice release") < textReadme.indexOf("## Networks"));
        assertTrue(textReadme.contains("**0.8.1**"), textReadme);
        assertTrue(textReadme.startsWith("# Raposza Network Operations Feed\n\n**The web page: ["), textReadme);
        assertTrue(textReadme.indexOf("**The web page: [") < posLink, "the page is linked above the contract");
        assertTrue(textReadme.contains("| MainNet | 0.7.5 | - | - | 0.7 | 0.8.0 | 2026-09-21 | 0.8.1 |"),
                textReadme);
        assertTrue(textReadme.contains("| sv\\_one | 0.8.1 | scan.sv-one.example | 2: seq-2.sv-one.example<br>"
                + "1: seq-1.sv-one.example *(legacy)* |"), textReadme);
        assertTrue(textReadme.contains("### TestNet\n\n0 nodes.\n\nNo node reported."), textReadme);
        assertTrue(textReadme.contains("Not the production feed"), "a rehearsal repository says so on top");
        assertTrue(textReadme.contains("\n" + GitPublish.LINE_STAMP + "2026-09-18T06:00:00Z.\n"), textReadme);
        assertFalse(show(dirBare, "main", GitPublish.NAME_CONTRACT).contains("history.yml"));
    }


    @Test
    void commitsWhenOnlyWhatTheReadmeShowsMoves() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        String textVersions = show(dirBare, "main", GitPublish.NAME_VERSIONS);
        Map<String, Object> mapListed = ds("pub_2", "2026-09-18T06:01:00Z", "0.8.1");
        mapListed.put("sources", List.of(Dataset.map("id", "test-source", "url", "https://example.invalid/source",
                "publisher", "test", "authority", "OFFICIAL", "pollSeconds", Integer.valueOf(300))));
        pub.turn(mapListed);
        assertEquals(2, commits(dirBare, "main"), "the source list is shown in the README alone");
        assertEquals(textVersions, show(dirBare, "main", GitPublish.NAME_VERSIONS).replace(
                "2026-09-18T06:01:00Z", "2026-09-18T06:00:00Z"), "versions.yml carries no source list");
        assertTrue(show(dirBare, "main", GitPublish.NAME_README).contains("test-source"));

        pub.turn(ds("pub_3", "2026-09-18T06:02:00Z", "0.8.1", "https://seq-3.sv-one.example/"));
        assertEquals(3, commits(dirBare, "main"), "a moved sequencer host commits");
        assertTrue(show(dirBare, "main", GitPublish.NAME_VERSIONS).contains("url: \"https://seq-3.sv-one.example/\""),
                "versions.yml carries the sequencers the README shows");
        assertTrue(show(dirBare, "main", GitPublish.NAME_README).contains("seq-3.sv-one.example"));
    }


    @Test
    void replacesTheOldShapeWithoutRewritingTheLog() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        Map<String, Object> mapDs = ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1");
        Path dirOld = Files.createDirectories(dirTmp.resolve("old"));
        git(dirOld, "init", "-q", "-b", "main", ".");
        git(dirOld, "config", "user.name", "test");
        git(dirOld, "config", "user.email", "test@example.invalid");
        Files.write(dirOld.resolve(GitPublish.NAME_VERSIONS),
                Versions.yaml(mapDs).getBytes(StandardCharsets.UTF_8));
        Files.write(dirOld.resolve("history.yml"), "history:\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dirOld.resolve(GitPublish.NAME_README), "# the contract\n".getBytes(StandardCharsets.UTF_8));
        git(dirOld, "add", "-A");
        git(dirOld, "commit", "-q", "-m", "publication pub_0");
        git(dirOld, "push", "-q", dirBare.toUri().toString(), "HEAD:refs/heads/main");
        String shaOld = git(dirBare, "rev-parse", "main").trim();

        GitPublish.of(spec(dirBare, -1)).turn(ds("pub_2", "2026-09-18T06:01:00Z", "0.8.1"));
        assertEquals(2, commits(dirBare, "main"), "the first turn replaces the shape though no value moved");
        assertEquals(shaOld, git(dirBare, "rev-parse", "main~1").trim(), "the old commit is kept");
        List<String> lstTracked = new ArrayList<>(
                List.of(git(dirBare, "ls-tree", "--name-only", "main").trim().split("\n")));
        Collections.sort(lstTracked);
        List<String> lstWanted = new ArrayList<>(
                List.of(GitPublish.NAME_VERSIONS, GitPublish.NAME_README, GitPublish.NAME_CONTRACT));
        Collections.sort(lstWanted);
        assertEquals(lstWanted, lstTracked, "history.yml is gone from the branch");
    }


    @Test
    void leavesNothingOfAnEarlierShapeBehind() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        pub.turn(ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1"));
        Path dirTree = dirTmp.resolve("work").resolve("data");
        Files.write(dirTree.resolve("state.json"), "{}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dirTree.resolve("versions.txt"), "MainNet: 0.7.5\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dirTree.resolve("history.yml"), "history:\n".getBytes(StandardCharsets.UTF_8));

        pub.turn(ds("pub_2", "2026-09-18T06:02:00Z", "0.8.2"));
        List<String> lstTracked = new ArrayList<>(
                List.of(git(dirBare, "ls-tree", "--name-only", "main").trim().split("\n")));
        List<String> lstWanted = new ArrayList<>(
                List.of(GitPublish.NAME_VERSIONS, GitPublish.NAME_README, GitPublish.NAME_CONTRACT));
        Collections.sort(lstTracked);
        Collections.sort(lstWanted);
        assertEquals(lstWanted, lstTracked,
                "the branch holds exactly the three files this publication writes");
    }


    @Test
    void writesNothingWhenNothingWasObserved() throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on the path");
        Path dirBare = bare();
        GitPublish pub = GitPublish.of(spec(dirBare, -1));

        Map<String, Object> mapEmpty = ds("pub_1", "2026-09-18T06:00:00Z", "0.8.1");
        Dataset.meta(mapEmpty).put("content", "EMPTY");
        pub.turn(mapEmpty);
        assertEquals(0, commits(dirBare, "main"), "an environment that observed nothing publishes nothing");
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


    /**
     * A corpus of the shape the publication reduces: three networks carrying a
     * scheduled version, a minimum and the next upgrade, and one release event
     * behind `splice-latest`. MainNet also reports what it runs and one Super
     * Validator node, which lists a sequencer on the current serial and one on
     * the previous.
     */
    private static Map<String, Object> ds(String idPublication, String stampCreated, String verSplice) {
        return ds(idPublication, stampCreated, verSplice, "https://seq-2.sv-one.example/");
    }


    private static Map<String, Object> ds(String idPublication, String stampCreated, String verSplice,
            String urlSequencer) {
        Map<String, Object> mapMain = net("MAINNET", "0.7.5", "0.7", "0.8.0", "2026-09-21", idPublication,
                stampCreated);
        mapMain.put("deployment", Dataset.map("version", "0.8.1", "svVersion", "0.8.1", "serialId", "2"));
        mapMain.put("superValidators", List.of(Dataset.map(
                "name", "sv_one",
                "version", "0.8.1",
                "scan", "https://scan.sv-one.example/api/scan",
                "sequencers", List.of(
                        Dataset.map("serial", "2", "url", urlSequencer),
                        Dataset.map("serial", "1", "url", "https://seq-1.sv-one.example/")))));
        return Dataset.map(
                "metadata", Dataset.map("publicationId", idPublication, "createdAt", stampCreated,
                        "content", GitPublish.CONTENT_OBSERVED),
                "networks", List.of(
                        mapMain,
                        net("TESTNET", "0.8.0", "0.7", null, null, idPublication, stampCreated),
                        net("DEVNET", "0.8.1", "0.7", "0.8.2", "2026-09-24", idPublication, stampCreated)),
                "events", List.of(Dataset.map(
                        "kind", "SOFTWARE_RELEASE",
                        "version", Dataset.map("value", verSplice))));
    }


    private static Map<String, Object> net(String nameNetwork, String verScheduled, String verMinimum,
            String verNext, String dayNext, String idPublication, String stampCreated) {
        return Dataset.map(
                "network", nameNetwork,
                "splice", Dataset.map("scheduledVersion", verScheduled, "minimumVersion", verMinimum),
                "next", Dataset.map("version", verNext, "from", dayNext),
                "publicationId", idPublication,
                "updatedAt", stampCreated);
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
