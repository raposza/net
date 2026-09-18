/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The git publication channel: the third delivery channel, beside the api and
 * the web page.
 *
 * The repository holds two files and nothing else. One is the whole published
 * dataset as a single json document, so a consumer fetches one url and a reader
 * who arrives with no tooling sees the data itself. The other is the README,
 * which under a two-file repository is the only place the consumer contract can
 * live and therefore carries all of it.
 *
 * A commit is made only when the dataset MOVED. Every publication carries a
 * fresh publication id and creation stamp, so a byte comparison would commit on
 * every turn and say nothing at all; the comparison is made over a form of the
 * document with those two fields blanked, while the file that is committed
 * carries their real values.
 *
 * Liveness is a second branch. A repository that is correctly silent looks
 * exactly like a repository whose writer has stopped, so the heartbeat commits a
 * timestamp on a branch of its own at its own interval. The data branch receives
 * real changes only, is never rewritten, and is never touched by the heartbeat.
 *
 * The commit subject names the publication rather than describing the change. A
 * readable subject is a sentence about what moved, which needs a diff over
 * published events, and nothing publishes events yet.
 *
 * Nothing here can fail a publication: the database and the served dataset are
 * the canonical channel and this is a mirror of it. Every path reports and
 * returns, a failed push is retried on a later turn, and the shell out to git is
 * the transport an operator can reproduce by hand.
 *
 * Author Claude/bentzn
 */
public final class GitPublish {

    /** The dataset, one document. */
    public static final String NAME_ARTEFACT = "feed.json";

    /** The consumer contract. */
    public static final String NAME_README = "README.md";

    /** The liveness file, on the heartbeat branch only. */
    public static final String NAME_BEAT = "heartbeat.json";

    /**
     * The shortest heartbeat interval accepted. A mistyped interval is a commit
     * and a notification mail every few seconds and nothing else in the design
     * refuses it, so anything faster than this disables the heartbeat and says
     * so rather than being clamped silently.
     */
    public static final int SEC_BEAT_FLOOR = 60;

    private static final long SEC_TIMEOUT = 600L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Spec spec;

    private long msPushed;

    private long msBeat;


    /**
     * Where the channel publishes and how often.
     *
     * @param urlRemote the data repository of this environment, and no other
     * @param nameBranchData the branch carrying the data
     * @param nameBranchBeat the branch carrying the heartbeat
     * @param dirWork root of the working clones, one per branch
     * @param fileKey the private key this environment pushes with, or null to
     *        let ssh decide
     * @param fileKnownHosts the pinned host key, or null to let ssh decide
     * @param secPush shortest interval between pushes of the data branch; a
     *        commit made inside it waits for the next turn
     * @param secBeat heartbeat interval in seconds, negative to disable
     * @param nameEnvironment the environment name, written into the heartbeat
     *        and the README so a rehearsal repository cannot read as production
     */
    public record Spec(String urlRemote, String nameBranchData, String nameBranchBeat, Path dirWork,
            Path fileKey, Path fileKnownHosts, int secPush, int secBeat, String nameEnvironment) {
    }


    private GitPublish(Spec specUse) {
        this.spec = specUse;
        this.msPushed = 0L;
        this.msBeat = 0L;
    }


    /**
     * @param specUse where to publish
     * @return a channel on that specification
     */
    public static GitPublish of(Spec specUse) {
        return new GitPublish(specUse);
    }


    /**
     * @return the channel this environment is configured for, or null when it is
     *         switched off or has nowhere to push
     */
    public static GitPublish fromConfig() {
        if (!Config.publishGit())
            return null;
        String urlRemote = Config.gitRemote();
        if (urlRemote == null) {
            System.err.println("git publication is enabled and FEED_GIT_REMOTE is not set: nothing is published"
                    + " to git");
            return null;
        }
        return of(new Spec(urlRemote, Config.gitBranchData(), Config.gitBranchHeartbeat(),
                Config.gitPublishDir(), Config.gitKey(), Config.gitKnownHosts(),
                Config.gitPushIntervalSeconds(), Config.heartbeatSeconds(), Config.environment()));
    }


    /** @return the specification this channel runs on */
    public Spec spec() {
        return spec;
    }


    /**
     * Mirrors one publication and beats if it is due. Never throws: a mirror
     * that is down is not a reason to stop publishing.
     *
     * @param mapDs the corpus that was just published
     */
    public void turn(Map<String, Object> mapDs) {
        try {
            data(mapDs);
        }
        catch (IOException | RuntimeException e) {
            System.err.println("git publication: data branch failed: " + e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            beat(mapDs);
        }
        catch (IOException | RuntimeException e) {
            System.err.println("git publication: heartbeat failed: " + e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private void data(Map<String, Object> mapDs) throws IOException, InterruptedException {
        Path dirTree = spec.dirWork().resolve("data");
        ensure(dirTree, spec.nameBranchData());
        byte[] bytesNew = json(mapDs);
        Path fileArtefact = dirTree.resolve(NAME_ARTEFACT);
        if (!same(fileArtefact, bytesNew)) {
            Files.write(fileArtefact, bytesNew);
            Files.write(dirTree.resolve(NAME_README), readme(mapDs).getBytes(StandardCharsets.UTF_8));
            must(dirTree, "git", "add", "-A");
            if (exec(dirTree, "git", "diff", "--cached", "--quiet").code() != 0) {
                must(dirTree, "git", "commit", "-q", "-m", "publication " + idOf(mapDs));
                System.out.println("git publication: committed " + idOf(mapDs) + " to " + spec.nameBranchData());
            }
        }
        if (System.currentTimeMillis() - msPushed < spec.secPush() * 1000L)
            return;
        if (push(dirTree, spec.nameBranchData())) {
            msPushed = System.currentTimeMillis();
        }
    }


    private void beat(Map<String, Object> mapDs) throws IOException, InterruptedException {
        if (spec.secBeat() < 0)
            return;
        if (spec.secBeat() < SEC_BEAT_FLOOR) {
            System.err.println("git publication: heartbeat interval " + spec.secBeat() + " s is below the floor of "
                    + SEC_BEAT_FLOOR + " s: the heartbeat is disabled");
            return;
        }
        long msNow = System.currentTimeMillis();
        if (msNow - msBeat < spec.secBeat() * 1000L)
            return;
        msBeat = msNow;
        Path dirTree = spec.dirWork().resolve("beat");
        ensure(dirTree, spec.nameBranchBeat());
        Map<String, Object> mapBeat = Dataset.map(
                "checkedAt", Dataset.iso(Instant.ofEpochMilli(msNow)),
                "environment", spec.nameEnvironment(),
                "publicationId", idOf(mapDs),
                "note", "liveness only: this branch carries no data and no history worth reading");
        Files.write(dirTree.resolve(NAME_BEAT), json(mapBeat));
        must(dirTree, "git", "add", "-A");
        if (exec(dirTree, "git", "diff", "--cached", "--quiet").code() == 0)
            return;
        must(dirTree, "git", "commit", "-q", "-m", "heartbeat " + Dataset.iso(Instant.ofEpochMilli(msNow)));
        push(dirTree, spec.nameBranchBeat());
    }


    /**
     * Brings a working clone into existence on one branch. An empty remote
     * leaves the branch unborn, which is the state the first publication
     * commits into, so the repository is created by hand and populated by the
     * worker.
     */
    private void ensure(Path dirTree, String nameBranch) throws IOException, InterruptedException {
        if (Files.isDirectory(dirTree.resolve(".git"))) {
            exec(dirTree, "git", "fetch", "-q", "origin", refspec(nameBranch));
            return;
        }
        Files.createDirectories(dirTree);
        must(dirTree, "git", "init", "-q", "-b", nameBranch, ".");
        must(dirTree, "git", "config", "user.name", "raposza-net");
        must(dirTree, "git", "config", "user.email", "feed@raposza.com");
        must(dirTree, "git", "remote", "add", "origin", spec.urlRemote());
        if (exec(dirTree, "git", "fetch", "-q", "origin", refspec(nameBranch)).code() == 0) {
            must(dirTree, "git", "reset", "-q", "--hard", "refs/remotes/origin/" + nameBranch);
        }
    }


    /**
     * @return true when the push succeeded or there was nothing to push; false
     *         when it failed, which leaves the commits for a later turn
     */
    private boolean push(Path dirTree, String nameBranch) throws IOException, InterruptedException {
        if (!ahead(dirTree, nameBranch))
            return true;
        Exec execPush = exec(dirTree, "git", "push", "-q", "origin", "HEAD:refs/heads/" + nameBranch);
        if (execPush.code() != 0) {
            System.err.println("git publication: push to " + nameBranch + " failed: " + execPush.textErr().trim());
            return false;
        }
        exec(dirTree, "git", "fetch", "-q", "origin", refspec(nameBranch));
        System.out.println("git publication: pushed " + nameBranch);
        return true;
    }


    /**
     * @return true when the working clone holds commits the remote branch does
     *         not, an absent remote branch counting as all of them
     */
    private boolean ahead(Path dirTree, String nameBranch) throws IOException, InterruptedException {
        if (exec(dirTree, "git", "rev-parse", "--verify", "-q", "HEAD").code() != 0)
            return false;
        String refRemote = "refs/remotes/origin/" + nameBranch;
        if (exec(dirTree, "git", "rev-parse", "--verify", "-q", refRemote).code() != 0)
            return true;
        return !"0".equals(text(must(dirTree, "git", "rev-list", "--count", refRemote + "..HEAD")).trim());
    }


    /**
     * The change unit. The publication id and the creation stamp move on every
     * turn by design, so they are blanked before the comparison; everything
     * else, including the source health block, counts as a change.
     *
     * @return true when the file on disk says the same thing as the bytes
     */
    private static boolean same(Path fileOld, byte[] bytesNew) {
        if (!Files.isRegularFile(fileOld))
            return false;
        try {
            return canonical(Files.readAllBytes(fileOld)).equals(canonical(bytesNew));
        }
        catch (IOException e) {
            return false;
        }
    }


    private static String canonical(byte[] bytesJson) throws IOException {
        Map<String, Object> mapDs = MAPPER.readValue(bytesJson, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> mapMeta = Dataset.meta(mapDs);
        return MAPPER.writeValueAsString(blank(mapDs, String.valueOf(mapMeta.get("publicationId")),
                String.valueOf(mapMeta.get("createdAt"))));
    }


    /**
     * Blanks every value that is the publication's own id or its creation
     * stamp, wherever in the document it appears. Both are repeated through the
     * corpus - on every network, on every event - so blanking the metadata
     * block alone leaves a copy in most records and every publication then
     * reads as a change: measured on DEV as one commit a minute.
     *
     * Matching by VALUE rather than by field name keeps this true as the
     * corpus grows. A timestamp that carries a real moment differs from the
     * creation stamp and counts as a change, with no list of field names to
     * keep current. The cost is that a real moment falling in the same second
     * as the publication is not seen until the next one.
     *
     * @param objAny a node of the parsed document, edited in place
     * @param idPublication the publication id to blank
     * @param stampCreated the creation stamp to blank
     * @return the node
     */
    private static Object blank(Object objAny, String idPublication, String stampCreated) {
        if (objAny instanceof Map) {
            Map<String, Object> mapNode = cast(objAny);
            for (Map.Entry<String, Object> entOne : mapNode.entrySet()) {
                entOne.setValue(blank(entOne.getValue(), idPublication, stampCreated));
            }
            return mapNode;
        }
        if (objAny instanceof List) {
            List<Object> lstNode = castList(objAny);
            for (int cntItem = 0; cntItem < lstNode.size(); cntItem++) {
                lstNode.set(cntItem, blank(lstNode.get(cntItem), idPublication, stampCreated));
            }
            return lstNode;
        }
        if (objAny instanceof String && (objAny.equals(idPublication) || objAny.equals(stampCreated)))
            return "";
        return objAny;
    }


    private static byte[] json(Map<String, Object> mapAny) throws IOException {
        return (MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(mapAny) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }


    private static String idOf(Map<String, Object> mapDs) {
        return String.valueOf(Dataset.meta(mapDs).get("publicationId"));
    }


    private static String refspec(String nameBranch) {
        return "+refs/heads/" + nameBranch + ":refs/remotes/origin/" + nameBranch;
    }


    /**
     * The whole consumer contract, regenerated with every data commit. Three
     * separate things a consumer depends on are stated here because there is
     * nowhere else for them to be stated: the path, the field names, and the
     * meaning of a field. The third is the one that breaks logic without
     * breaking a parser, so it is named before either of the others.
     *
     * @param mapDs the corpus being committed
     * @return the README text
     */
    private String readme(Map<String, Object> mapDs) {
        StringBuilder sbOut = new StringBuilder(4096);
        sbOut.append("# Raposza Network Operations Feed - data\n\n");
        sbOut.append("Machine-generated. Nothing in this repository is edited by hand, and a wrong\n");
        sbOut.append("publication is corrected by a further publication, never by rewriting history.\n\n");
        sbOut.append("Environment: ").append(spec.nameEnvironment()).append(".\n");
        if (!"prd".equals(spec.nameEnvironment())) {
            sbOut.append("This is not the production feed. It exists so the publication path is\n");
            sbOut.append("rehearsed, and its contents are not a statement about any network.\n");
        }
        sbOut.append("\n## Files\n\n");
        sbOut.append("- `").append(NAME_ARTEFACT).append("` - the published dataset, one document.\n");
        sbOut.append("- `").append(NAME_README).append("` - this file.\n");
        sbOut.append("\n## What the versions mean\n\n");
        sbOut.append("Every version here is SCHEDULED, not running. No source this feed reads reports\n");
        sbOut.append("what a network is currently running; what is published is what the operators\n");
        sbOut.append("have said they intend to do, and a schedule that has passed its date is not\n");
        sbOut.append("evidence that it happened. Do not gate a deployment on a field in this file\n");
        sbOut.append("without reading this paragraph again.\n");
        sbOut.append("\n## Stability\n\n");
        sbOut.append("Nothing is promised before version 1.0.0: not the file name, not the field\n");
        sbOut.append("names, not the shape, and not the MEANING of a field. A change of meaning is\n");
        sbOut.append("the dangerous one, because it breaks no parser and every decision made from\n");
        sbOut.append("one. `metadata.content` states what the values in this file are; while it\n");
        sbOut.append("reads PLACEHOLDER they carry the shape of the contract and not observed\n");
        sbOut.append("facts.\n");
        sbOut.append("\n## Freshness\n\n");
        sbOut.append("`metadata.publicationId` and `metadata.createdAt` identify the publication.\n");
        sbOut.append("They move on every publication, so this repository is committed only when the\n");
        sbOut.append("rest of the document changes: no commit here means nothing changed, and the\n");
        sbOut.append("stamps you read are those of the last change, not of the last check.\n");
        sbOut.append("\n## Branches\n\n");
        sbOut.append("- `").append(spec.nameBranchData()).append("` - the data. One commit per real change, never rewritten.\n");
        sbOut.append("- `").append(spec.nameBranchBeat()).append("` - liveness only. A timestamp, committed on a fixed interval\n");
        sbOut.append("  whether anything changed or not, so that a silent data branch can be told\n");
        sbOut.append("  apart from a writer that has stopped. It carries no data. Read nothing into\n");
        sbOut.append("  its contents beyond the fact that the writer was alive at that moment.\n");
        sbOut.append("\n## Content of this publication\n\n");
        sbOut.append("`metadata.content` is `").append(String.valueOf(Dataset.meta(mapDs).get("content")))
                .append("`.\n");
        return sbOut.toString();
    }


    private Exec must(Path dirWork, String... argsCmd) throws IOException, InterruptedException {
        Exec exec = exec(dirWork, argsCmd);
        if (exec.code() != 0)
            throw new IOException(String.join(" ", argsCmd) + " exited " + exec.code() + ": " + exec.textErr().trim());
        return exec;
    }


    private Exec exec(Path dirWork, String... argsCmd) throws IOException, InterruptedException {
        ProcessBuilder bldProc = new ProcessBuilder(argsCmd);
        bldProc.directory(dirWork.toFile());
        bldProc.environment().put("GIT_TERMINAL_PROMPT", "0");
        bldProc.environment().put("GIT_ASKPASS", "true");
        bldProc.environment().put("GIT_SSH_COMMAND", ssh());
        Process proc = bldProc.start();
        proc.getOutputStream().close();
        StringBuilder sbErr = new StringBuilder();
        Thread thrErr = new Thread(() -> {
            try {
                sbErr.append(new String(drain(proc.getErrorStream()), StandardCharsets.UTF_8));
            }
            catch (IOException e) {
                sbErr.append(e);
            }
        });
        thrErr.setDaemon(true);
        thrErr.start();
        byte[] bytesOut = drain(proc.getInputStream());
        if (!proc.waitFor(SEC_TIMEOUT, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException(String.join(" ", argsCmd) + " timed out after " + SEC_TIMEOUT + " s");
        }
        thrErr.join(5000L);
        return new Exec(proc.exitValue(), bytesOut, sbErr.toString());
    }


    /**
     * The transport. BatchMode refuses every prompt rather than hanging a
     * service on one, the identity is the only one offered so an agent cannot
     * substitute another, and the host key is checked against the pinned file.
     *
     * @return the command git runs for ssh
     */
    private String ssh() {
        StringBuilder sbCmd = new StringBuilder("ssh -o BatchMode=yes");
        if (spec.fileKey() != null) {
            sbCmd.append(" -o IdentitiesOnly=yes -i ").append(spec.fileKey().toAbsolutePath().normalize());
        }
        if (spec.fileKnownHosts() != null) {
            sbCmd.append(" -o StrictHostKeyChecking=yes -o UserKnownHostsFile=")
                    .append(spec.fileKnownHosts().toAbsolutePath().normalize());
        }
        return sbCmd.toString();
    }


    private static byte[] drain(InputStream strmIn) throws IOException {
        ByteArrayOutputStream strmOut = new ByteArrayOutputStream();
        byte[] bufRead = new byte[65536];
        int cntRead;
        while ((cntRead = strmIn.read(bufRead)) >= 0) {
            strmOut.write(bufRead, 0, cntRead);
        }
        return strmOut.toByteArray();
    }


    private static String text(Exec exec) {
        return new String(exec.bytesOut(), StandardCharsets.UTF_8);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }


    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object objAny) {
        return (List<Object>) objAny;
    }


    private record Exec(int code, byte[] bytesOut, String textErr) {
    }
}
