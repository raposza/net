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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
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

    /** The same facts as the dataset, in four lines a person reads. */
    public static final String NAME_VERSIONS = "versions.txt";

    /** The liveness file, on the heartbeat branch only. */
    public static final String NAME_BEAT = "heartbeat.json";

    /**
     * The shortest heartbeat interval accepted. A mistyped interval is a commit
     * and a notification mail every few seconds and nothing else in the design
     * refuses it, so anything faster than this disables the heartbeat and says
     * so rather than being clamped silently.
     */
    public static final int SEC_BEAT_FLOOR = 60;

    private static final DateTimeFormatter FMT_DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);

    private static final long SEC_TIMEOUT = 600L;

    /** When this process started; the heartbeat reports the span since. */
    private static final Instant INST_START =
            ProcessHandle.current().info().startInstant().orElseGet(Instant::now);

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
            Files.write(dirTree.resolve(NAME_VERSIONS),
                    Versions.text(mapDs).getBytes(StandardCharsets.UTF_8));
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
                "uptimeHours", Double.valueOf(uptimeHours()),
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


    /**
     * How long this process has been up, in hours to two places. The moment is
     * the operating system's process start where it gives one, so a class that
     * happened to load late cannot hide a restart; otherwise it is when this
     * class was loaded, which is within seconds of it.
     *
     * @return the uptime in hours
     */
    private static double uptimeHours() {
        long secUp = Duration.between(INST_START, Instant.now()).toSeconds();
        return Math.round(secUp / 36.0) / 100.0;
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
        StringBuilder sbOut = new StringBuilder(8192);
        sbOut.append("# Raposza Network Operations Feed - data\n\n");
        sbOut.append("BaseNet can be wired to this feed with a cron job, so that a local\n");
        sbOut.append("customization is tested against what the networks are scheduled to run\n");
        sbOut.append("before it becomes a problem. Pull `").append(NAME_VERSIONS).append("` or `")
                .append(NAME_ARTEFACT).append("` on a schedule, compare it\n");
        sbOut.append("with what your own environment is pinned to, and fail your build when the\n");
        sbOut.append("two have drifted apart. This is a data feed, not a dashboard.\n\n");
        sbOut.append("Machine-generated. Nothing in this repository is edited by hand, and a wrong\n");
        sbOut.append("publication is corrected by a further publication, never by rewriting history.\n\n");
        sbOut.append("Environment: ").append(spec.nameEnvironment()).append(".\n");
        if (!"prd".equals(spec.nameEnvironment())) {
            sbOut.append("This is not the production feed. It exists so the publication path is\n");
            sbOut.append("rehearsed, and its contents are not a statement about any network.\n");
        }
        sbOut.append("\n## Files\n\n");
        sbOut.append("- `").append(NAME_ARTEFACT).append("` - the published dataset, one document.\n");
        sbOut.append("- `").append(NAME_VERSIONS).append("` - the same facts for a person, four lines.\n");
        sbOut.append("- `").append(NAME_README).append("` - this file.\n");
        sbOut.append("\n## Provenance\n\n");
        sbOut.append("Where this comes from, how often, and how it is put together. No value here\n");
        sbOut.append("is typed by a human.\n\n");
        sbOut.append("The sources polled, each on its own cadence, as this publication carries\n");
        sbOut.append("them:\n\n");
        sources(sbOut, mapDs);
        sbOut.append("\nEvery retrieved body is stored content-addressed by its sha256 and never\n");
        sbOut.append("overwritten, beside one journal line per attempt recording when the attempt\n");
        sbOut.append("ran and what it found. An identical body is not stored twice, and an attempt\n");
        sbOut.append("that retrieved nothing still leaves a line, so a source that is quiet and a\n");
        sbOut.append("poller that has stopped are different things in the record.\n\n");
        sbOut.append("Two sources are read into records. Each typed record of the SV Operations\n");
        sbOut.append("Schedule becomes one event, keyed by the record's own upstream id; each tag\n");
        sbOut.append("of the Splice repository becomes one event, keyed by the tag name. Every\n");
        sbOut.append("later body that moves a field of one of those records makes a revision and a\n");
        sbOut.append("change record. A record that disappears from a later body is marked\n");
        sbOut.append("withdrawn rather than cancelled, because upstream marks a cancellation and\n");
        sbOut.append("keeps the record, and a body that upstream merely re-sorted changes nothing.\n");
        sbOut.append("Every published event names the source, the observation and the normalizer it\n");
        sbOut.append("came from.\n\n");
        sbOut.append("EVERY NETWORK VERSION HERE IS SCHEDULED, NOT RUNNING. No source this feed\n");
        sbOut.append("reads reports what a network is currently running, so what you get is what\n");
        sbOut.append("the operators have said they intend to do, and a date that has arrived is not\n");
        sbOut.append("evidence that it happened. Per network, `").append(NAME_VERSIONS)
                .append("` shows the latest\n");
        sbOut.append("scheduled version whose date has arrived and the minimum version in force;\n");
        sbOut.append("the minimum is often stated upstream to a minor version only, so `0.7` there\n");
        sbOut.append("means `0.7.x`. The last line is different in kind: it is the highest version\n");
        sbOut.append("the tags endpoint carries, which says a release EXISTS and says nothing about\n");
        sbOut.append("any network taking it. A tag carries no date, so these records have none.\n");
        sbOut.append("\n## Stability\n\n");
        sbOut.append("The Canton networks are still evolving fast. We try our best to keep the\n");
        sbOut.append("interface and the schema stable and coherent, but if something changes\n");
        sbOut.append("upstream we may have to change them too.\n\n");
        sbOut.append("`metadata.content` states what the values in this file are: OBSERVED when\n");
        sbOut.append("they were derived from banked observations, EMPTY when this environment has\n");
        sbOut.append("banked none, and UNAVAILABLE when the index could not be read.\n");
        sbOut.append("\n## Freshness\n\n");
        sbOut.append("`metadata.publicationId` and `metadata.createdAt` identify the publication.\n");
        sbOut.append("They move on every publication, so this repository is committed only when the\n");
        sbOut.append("rest of the document changes: no commit here means nothing changed, and the\n");
        sbOut.append("stamps you read are those of the last change, not of the last check.\n");
        sbOut.append("\n## Branches\n\n");
        sbOut.append("- `").append(spec.nameBranchData()).append("` - the data. One commit per real change, never rewritten.\n");
        sbOut.append("- `").append(spec.nameBranchBeat()).append("` - liveness only. A timestamp and the publisher's uptime in\n");
        sbOut.append("  hours, committed on a fixed interval whether anything changed or not, so\n");
        sbOut.append("  that a silent data branch can be told apart from a writer that has stopped.\n");
        sbOut.append("  It carries no data. Read nothing into its contents beyond the fact that the\n");
        sbOut.append("  writer was alive at that moment.\n");
        sbOut.append("\n## Content of this publication\n\n");
        sbOut.append("`metadata.content` is `").append(String.valueOf(Dataset.meta(mapDs).get("content")))
                .append("`.\n");
        return sbOut.toString();
    }


    /**
     * The source list of the publication itself, so the cadences stated here are
     * the ones the build actually runs on and cannot drift from the registry.
     *
     * @param sbOut the README being built
     * @param mapDs the corpus being committed
     */
    private static void sources(StringBuilder sbOut, Map<String, Object> mapDs) {
        Object objList = mapDs.get("sources");
        int cntListed = 0;
        if (objList instanceof List) {
            for (Object objOne : (List<?>) objList) {
                if (!(objOne instanceof Map))
                    continue;
                Map<?, ?> mapOne = (Map<?, ?>) objOne;
                sbOut.append("- `").append(mapOne.get("id")).append("` - ").append(mapOne.get("publisher"))
                        .append(", authority ").append(mapOne.get("authority"))
                        .append(", polled every ").append(mapOne.get("pollSeconds")).append(" s\n");
                cntListed++;
            }
        }
        if (cntListed == 0) {
            sbOut.append("- this publication carries no source list\n");
        }
    }


    /**
     * Cuts a Release on the data repository. Releases are the only subscription
     * a stranger can register for by themselves, so this is the whole announce
     * path; a commit announces nothing to anyone.
     *
     * NOT CALLED YET. What decides that a publication deserves a Release, and
     * what the title says, both need a diff over published events, which
     * nothing produces. This is the transport waiting for that caller.
     *
     * @param nameTag the tag to create, from nextTag
     * @param titleRelease the one-line subject a subscriber sees in their inbox
     * @param textBody the release body, which may be empty
     * @return true when GitHub created it
     */
    public boolean release(String nameTag, String titleRelease, String textBody) {
        String tokenGh = Config.githubToken();
        if (tokenGh == null) {
            System.err.println("git publication: no release credential is set, so no Release was cut for "
                    + nameTag);
            return false;
        }
        String slugRepo = slug(spec.urlRemote());
        if (slugRepo == null) {
            System.err.println("git publication: cannot read owner and repository from " + spec.urlRemote());
            return false;
        }
        try {
            Map<String, Object> mapReq = Dataset.map(
                    "tag_name", nameTag,
                    "target_commitish", spec.nameBranchData(),
                    "name", titleRelease,
                    "body", textBody == null ? "" : textBody,
                    "draft", Boolean.FALSE,
                    "prerelease", Boolean.FALSE,
                    "generate_release_notes", Boolean.FALSE,
                    "make_latest", "true");
            HttpRequest reqPost = HttpRequest
                    .newBuilder(URI.create("https://api.github.com/repos/" + slugRepo + "/releases"))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + tokenGh)
                    .header("X-GitHub-Api-Version", "2026-03-10")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30L))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(mapReq),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> respPost = HttpClient.newHttpClient().send(reqPost,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (respPost.statusCode() != 201) {
                System.err.println("git publication: release " + nameTag + " refused with "
                        + respPost.statusCode() + ": " + respPost.body());
                return false;
            }
            System.out.println("git publication: released " + nameTag);
            return true;
        }
        catch (IOException | RuntimeException e) {
            System.err.println("git publication: release " + nameTag + " failed: " + e);
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


    /**
     * The tag naming. A date rather than a version, because the repository is a
     * dataset and a version-shaped tag invites a consumer to read compatibility
     * into it; a suffix only where a day carries more than one.
     *
     * @param instNow the moment of the release
     * @param collTaken the tags the repository already holds
     * @return the first free tag for that day
     */
    public static String nextTag(Instant instNow, Collection<String> collTaken) {
        String nameBase = "v" + FMT_DAY.format(instNow);
        if (!collTaken.contains(nameBase))
            return nameBase;
        int cntTry = 2;
        while (collTaken.contains(nameBase + "." + cntTry)) {
            cntTry++;
        }
        return nameBase + "." + cntTry;
    }


    /**
     * @param dirTree a working clone
     * @return the tag names the remote holds, empty when it cannot be asked
     */
    public List<String> tagsRemote(Path dirTree) {
        List<String> lstTag = new ArrayList<>();
        try {
            Exec execLs = exec(dirTree, "git", "ls-remote", "--tags", "origin");
            if (execLs.code() != 0) {
                System.err.println("git publication: cannot list tags: " + execLs.textErr().trim());
                return lstTag;
            }
            for (String lineRef : text(execLs).split("\n")) {
                int posRef = lineRef.indexOf("refs/tags/");
                if (posRef < 0) {
                    continue;
                }
                String nameTag = lineRef.substring(posRef + "refs/tags/".length()).trim();
                if (!nameTag.endsWith("^{}")) {
                    lstTag.add(nameTag);
                }
            }
        }
        catch (IOException | RuntimeException e) {
            System.err.println("git publication: cannot list tags: " + e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lstTag;
    }


    /**
     * Reads owner and repository out of the remote, for the REST call. Both the
     * scp-like and the url forms are accepted, since either can appear in an
     * environment file.
     *
     * @param urlRemote the remote as configured
     * @return "owner/repo", or null when the remote is not a github remote
     */
    static String slug(String urlRemote) {
        if (urlRemote == null)
            return null;
        String textCut = urlRemote.trim();
        int posHost = textCut.indexOf("github.com");
        if (posHost < 0)
            return null;
        textCut = textCut.substring(posHost + "github.com".length());
        if (textCut.startsWith(":") || textCut.startsWith("/")) {
            textCut = textCut.substring(1);
        }
        if (textCut.endsWith(".git")) {
            textCut = textCut.substring(0, textCut.length() - ".git".length());
        }
        while (textCut.endsWith("/")) {
            textCut = textCut.substring(0, textCut.length() - 1);
        }
        return textCut.chars().filter(chOne -> chOne == '/').count() == 1 && !textCut.startsWith("/")
                ? textCut
                : null;
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
