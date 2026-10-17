/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The git publication channel: the third delivery channel, beside the api and
 * the web page.
 *
 * The repository holds three files and nothing else. `versions.yml` is the
 * current state, the few values a consumer acts on, in a form a person reads
 * without tooling. `README.md` is the same data as tables, because it is the
 * page GitHub shows first and a person arriving at the repository should see
 * the data before anything about it. `REAL_README.md`, linked from the top of
 * the README, carries the consumer contract, because under a repository of this
 * size there is nowhere else for it to live.
 *
 * A commit is made only when something MOVED. The timestamp moves on every
 * publication by design, so `versions.yml` is compared with that one line
 * dropped and the README with its one stamp line dropped; the files that are
 * committed carry them. The README is compared as well as `versions.yml`
 * because it shows the sequencer hosts, which `versions.yml` does not carry.
 *
 * The data branch is ordinary git. Every change is a commit on top of the last
 * one, nothing is amended and nothing is force-pushed. No history file is
 * kept; the commit log is the record.
 *
 * Liveness is a second branch. A repository that is correctly silent looks
 * exactly like a repository whose writer has stopped, so the heartbeat commits a
 * timestamp on a branch of its own at its own interval, and never touches the
 * data branch.
 *
 * The commit subject names the publication rather than describing the change. A
 * readable subject is a sentence about what moved, which needs a diff over
 * published events, and nothing derives one.
 *
 * Nothing here can fail a publication: the database and the served dataset are
 * the canonical channel and this is a mirror of it. Every path reports and
 * returns, a failed push is retried on a later turn, and the shell out to git is
 * the transport an operator can reproduce by hand.
 *
 * Author Claude/bentzn
 */
public final class GitPublish {

    /** The current state, the values a consumer acts on. */
    public static final String NAME_VERSIONS = "versions.yml";

    /** The data as tables, the page a visitor to the repository sees first. */
    public static final String NAME_README = "README.md";

    /** The consumer contract, linked from the top of the README. */
    public static final String NAME_CONTRACT = "REAL_README.md";

    /**
     * Opens the one line of the README that moves on every publication. That
     * line alone is dropped when the README is compared, exactly as the
     * timestamp line is dropped from `versions.yml`.
     */
    static final String LINE_STAMP = "Values as of ";

    /** The liveness file, on the heartbeat branch only. */
    public static final String NAME_BEAT = "heartbeat.json";

    /**
     * What a publication must be made of before anything is written. An
     * environment that has banked nothing derives no version for any network, and
     * a file of nulls would read as three networks with nothing scheduled rather
     * than as an environment with nothing to say.
     */
    public static final String CONTENT_OBSERVED = "OBSERVED";

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


    /**
     * The data branch. All three files are built in memory before any is
     * written.
     *
     * A file of an earlier shape left on the branch counts as a change, so the
     * first turn of a build that stops writing a file removes it even when no
     * value moved.
     *
     * @param mapDs the corpus that was just published
     */
    private void data(Map<String, Object> mapDs) throws IOException, InterruptedException {
        if (!CONTENT_OBSERVED.equals(String.valueOf(Dataset.meta(mapDs).get("content"))))
            return;
        Path dirTree = spec.dirWork().resolve("data");
        ensure(dirTree, spec.nameBranchData());
        String textVersions = Versions.yaml(mapDs);
        String textReadme = readme(mapDs);
        String textContract = contract(mapDs);
        Path fileVersions = dirTree.resolve(NAME_VERSIONS);
        Path fileReadme = dirTree.resolve(NAME_README);
        Path fileContract = dirTree.resolve(NAME_CONTRACT);
        boolean bMoved = !same(fileVersions, textVersions, Versions::withoutTimestamp)
                || !same(fileReadme, textReadme, GitPublish::withoutStamp)
                || !same(fileContract, textContract, UnaryOperator.identity())
                || !strays(dirTree).isEmpty();
        if (bMoved) {
            Files.write(fileVersions, textVersions.getBytes(StandardCharsets.UTF_8));
            Files.write(fileReadme, textReadme.getBytes(StandardCharsets.UTF_8));
            Files.write(fileContract, textContract.getBytes(StandardCharsets.UTF_8));
            prune(dirTree);
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


    /**
     * The branch holds exactly the files this class writes and no others.
     *
     * Without this, a file belonging to an earlier shape of the publication
     * survives its own removal for ever: the working clone is checked out from
     * the remote and carries it, nothing here rewrites it, `git add -A` sees no
     * change in it, and every commit keeps it. A reader then finds two documents,
     * one of them frozen at the moment the code stopped writing it, and no way to
     * tell from the repository which is which.
     *
     * Names beginning with a dot are left alone: `.git` is the branch itself, and
     * a repository setting a maintainer added by hand is not this code's to
     * delete.
     *
     * @param dirTree the working clone of the data branch
     */
    private static void prune(Path dirTree) throws IOException {
        for (Path fileOne : strays(dirTree)) {
            Files.delete(fileOne);
            System.out.println("git publication: removed " + fileOne.getFileName()
                    + ", which this publication does not write");
        }
    }


    /**
     * @param dirTree the working clone of the data branch
     * @return the regular files at its top that this class does not write,
     *         names beginning with a dot excepted
     */
    private static List<Path> strays(Path dirTree) throws IOException {
        List<String> lstKeep = List.of(NAME_VERSIONS, NAME_README, NAME_CONTRACT);
        List<Path> lstStray = new ArrayList<>();
        try (DirectoryStream<Path> strmFile = Files.newDirectoryStream(dirTree)) {
            for (Path fileOne : strmFile) {
                String nameOne = fileOne.getFileName().toString();
                if (nameOne.startsWith(".") || !Files.isRegularFile(fileOne) || lstKeep.contains(nameOne))
                    continue;
                lstStray.add(fileOne);
            }
        }
        return lstStray;
    }


    /**
     * @param fileOld the file on the branch, which need not exist
     * @param textNew the text that would be written
     * @param fnStable drops what moves on every publication by design
     * @return true when the file on disk states the same thing
     */
    private static boolean same(Path fileOld, String textNew, UnaryOperator<String> fnStable) {
        if (!Files.isRegularFile(fileOld))
            return false;
        try {
            return fnStable.apply(Files.readString(fileOld, StandardCharsets.UTF_8)).equals(fnStable.apply(textNew));
        }
        catch (IOException e) {
            return false;
        }
    }


    /**
     * @param textReadme the text of a README this class wrote
     * @return it without its stamp line
     */
    static String withoutStamp(String textReadme) {
        StringBuilder sbOut = new StringBuilder(textReadme.length());
        for (String lineOne : textReadme.split("\n")) {
            if (lineOne.startsWith(LINE_STAMP))
                continue;
            sbOut.append(lineOne).append('\n');
        }
        return sbOut.toString();
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
     * The README: the data first, the way the web page shows it, so a person
     * arriving at the repository sees what the networks run before anything
     * about how it is published. It is also the page for readers who cannot reach
     * the web page, so the link to that page opens it, and the link to the
     * contract follows.
     *
     * It carries the part of the page that moves only when the networks move: no
     * planned events, whose window rolls daily, no change log, which the commit
     * log already is, and no node that upstream could not read at this one
     * poll, which would commit a failure and its recovery. What the page alone
     * shows is said at the top.
     *
     * Everything here is drawn from the publication, never typed. The one line
     * that moves on every publication opens with LINE_STAMP and is dropped when
     * the README is compared, so this file commits when what it shows changes
     * and at no other time.
     *
     * @param mapDs the corpus being committed
     * @return the text of `README.md`
     */
    String readme(Map<String, Object> mapDs) {
        StringBuilder sbOut = new StringBuilder(16384);
        sbOut.append("# Raposza Network Operations Feed\n\n");
        String urlPage = webPage(spec.nameEnvironment());
        sbOut.append("**The web page: [").append(md(urlPage)).append("](").append(urlPage).append(")** - this\n");
        sbOut.append("README carries part of it, for readers who cannot reach it. The planned events\n");
        sbOut.append("of the coming weeks, the log of what changed, and the freshness of each source\n");
        sbOut.append("are on the page only.\n\n");
        sbOut.append("**Read [").append(NAME_CONTRACT).append("](").append(NAME_CONTRACT)
                .append(") before you build on this data** - what every field\n");
        sbOut.append("means, where it comes from, and what is guaranteed.\n\n");
        if (!"prd".equals(spec.nameEnvironment())) {
            sbOut.append("> **Not the production feed.** Environment: ").append(md(spec.nameEnvironment()))
                    .append(". It exists so the\n");
            sbOut.append("> publication path is rehearsed, and its contents are not a statement about\n");
            sbOut.append("> any network.\n\n");
        }
        sbOut.append("What is deployed on the Canton Network, what is scheduled, and which Super\n");
        sbOut.append("Validator node runs what. Machine-generated from public sources; nothing here\n");
        sbOut.append("is edited by hand. The same values are in [`").append(NAME_VERSIONS).append("`](")
                .append(NAME_VERSIONS).append(") for machines.\n");
        sbOut.append("\n## Latest Splice release\n\n");
        String verLatest = Versions.latest(mapDs);
        sbOut.append(verLatest == null ? "-" : "**" + md(verLatest) + "**").append("\n\n");
        sbOut.append("The highest version the Splice tags endpoint carries. A release existing is\n");
        sbOut.append("not a network running it; what each network runs is below.\n");
        networksTable(sbOut, mapDs);
        superValidators(sbOut, mapDs);
        sourcesTable(sbOut, mapDs);
        sbOut.append("\n---\n\n");
        sbOut.append(LINE_STAMP).append(md(Dataset.meta(mapDs).get("createdAt"))).append(".\n");
        sbOut.append("A commit is made only when something shown here changes, so this is when a\n");
        sbOut.append("value last moved, not when the feed last checked. The `").append(spec.nameBranchBeat())
                .append("` branch says\n");
        sbOut.append("the writer is alive.\n");
        return sbOut.toString();
    }


    private static void networksTable(StringBuilder sbOut, Map<String, Object> mapDs) {
        sbOut.append("\n## Networks\n\n");
        sbOut.append("Current is the last confirmed upgrade on the SV Operations Schedule whose date\n");
        sbOut.append("has arrived; the schedule states what the operators intend, so a date that has\n");
        sbOut.append("arrived is not proof the upgrade ran. SVs on it counts the Super Validator\n");
        sbOut.append("nodes whose own roster entry reports that version. Minimum is the oldest\n");
        sbOut.append("version a validator may run. Next and Due are the earliest upgrade still\n");
        sbOut.append("ahead, confirmed or not. Reported is the version the network's own info\n");
        sbOut.append("document states.\n\n");
        sbOut.append("| Network | Current (scheduled) | SVs on it | Since | Minimum | Next | Due | Reported |\n");
        sbOut.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> mapNetwork : networks(mapDs)) {
            Map<String, Object> mapSplice = sub(mapNetwork, "splice");
            Map<String, Object> mapNext = sub(mapNetwork, "next");
            Map<String, Object> mapDeploy = sub(mapNetwork, "deployment");
            sbOut.append("| ").append(md(nameNetwork(mapNetwork)))
                    .append(" | ").append(md(val(mapSplice, "scheduledVersion")))
                    .append(" | ").append(md(onScheduled(sub(mapNetwork, "roster"))))
                    .append(" | ").append(md(val(mapSplice, "scheduledFrom")))
                    .append(" | ").append(md(val(mapSplice, "minimumVersion")))
                    .append(" | ").append(md(val(mapNext, "version")))
                    .append(" | ").append(md(val(mapNext, "from")))
                    .append(" | ").append(md(val(mapDeploy, "version")))
                    .append(" |\n");
        }
    }


    /**
     * One table per network, as the web page has it. A sequencer whose serial is
     * below the one the network reports as current is marked legacy, one above it
     * successor; where the network reports no serial, none is marked.
     */
    private static void superValidators(StringBuilder sbOut, Map<String, Object> mapDs) {
        sbOut.append("\n## Super Validators\n\n");
        sbOut.append("The hosts each Super Validator node publishes, as the network's own DSO\n");
        sbOut.append("document states them. Scan is the node's public read endpoint. A participant\n");
        sbOut.append("connects to a sequencer, and the sequencer changes at every synchronizer\n");
        sbOut.append("serial: while an upgrade is in flight a node lists one per serial, highest\n");
        sbOut.append("first. A serial below the one the network reports as current is marked\n");
        sbOut.append("legacy, and one above it successor.\n");
        for (Map<String, Object> mapNetwork : networks(mapDs)) {
            String serialCurrent = val(sub(mapNetwork, "deployment"), "serialId");
            List<Map<String, Object>> lstNode = maps(mapNetwork.get("superValidators"));
            sbOut.append("\n### ").append(md(nameNetwork(mapNetwork))).append("\n\n");
            sbOut.append(lstNode.size()).append(lstNode.size() == 1 ? " node" : " nodes");
            if (serialCurrent != null) {
                sbOut.append(", serial ").append(md(serialCurrent));
            }
            String textSpread = spread(sub(mapNetwork, "roster"));
            if (textSpread != null) {
                sbOut.append(": ").append(md(textSpread));
            }
            sbOut.append(".\n\n");
            if (lstNode.isEmpty()) {
                sbOut.append("No node reported.\n");
                continue;
            }
            sbOut.append("| Super Validator | Version | Scan | Sequencers |\n");
            sbOut.append("| --- | --- | --- | --- |\n");
            for (Map<String, Object> mapNode : lstNode) {
                List<String> lstSeq = new ArrayList<>();
                for (Map<String, Object> mapSeq : maps(mapNode.get("sequencers"))) {
                    String serialOne = val(mapSeq, "serial");
                    String textOne = md(serialOne) + ": " + md(host(val(mapSeq, "url")));
                    String nameRole = role(serialCurrent, serialOne);
                    if (nameRole != null) {
                        textOne += " *(" + nameRole + ")*";
                    }
                    lstSeq.add(textOne);
                }
                sbOut.append("| ").append(md(val(mapNode, "name")))
                        .append(" | ").append(md(val(mapNode, "version")))
                        .append(" | ").append(md(host(val(mapNode, "scan"))))
                        .append(" | ").append(lstSeq.isEmpty() ? "-" : String.join("<br>", lstSeq))
                        .append(" |\n");
            }
        }
    }


    /**
     * @param nameEnvironment the environment this channel publishes
     * @return the web page that environment serves
     */
    static String webPage(String nameEnvironment) {
        if ("prd".equals(nameEnvironment))
            return "https://net.raposza.com/";
        if ("rc".equals(nameEnvironment) || "stg".equals(nameEnvironment) || "dev".equals(nameEnvironment))
            return "https://" + nameEnvironment + ".net.raposza.com/";
        return "http://127.0.0.1:" + Config.httpPort() + "/";
    }


    /**
     * @param mapRoster the network's roster counts, or null
     * @return how many nodes report the scheduled version out of all, or null
     *         where either is unknown
     */
    static String onScheduled(Map<String, Object> mapRoster) {
        String cntNodes = val(mapRoster, "nodes");
        if (cntNodes == null || "0".equals(cntNodes) || val(mapRoster, "scheduledVersion") == null)
            return null;
        return val(mapRoster, "onScheduled") + " / " + cntNodes;
    }


    /**
     * The versions the nodes report, as counts. Nodes upstream could not read at
     * this poll are not counted apart: they keep the version last read, and
     * counting them would commit every failure and its recovery.
     *
     * @param mapRoster the network's roster counts, or null
     * @return for example 12 on 0.9.0, 1 on 0.8.4; null where nothing is counted
     */
    static String spread(Map<String, Object> mapRoster) {
        List<Map<String, Object>> lstVersion = maps(mapRoster == null ? null : mapRoster.get("versions"));
        List<String> lstPart = new ArrayList<>();
        for (Map<String, Object> mapOne : lstVersion) {
            lstPart.add(val(mapOne, "count") + " on " + val(mapOne, "version"));
        }
        String cntUnreported = val(mapRoster, "unreported");
        if (cntUnreported != null && !"0".equals(cntUnreported)) {
            lstPart.add(cntUnreported + " not reported");
        }
        return lstPart.isEmpty() ? null : String.join(", ", lstPart);
    }


    /**
     * @param serialCurrent the serial the network reports as current, or null
     * @param serialOne the serial of one sequencer
     * @return legacy below the current serial, successor above it, null when it
     *         is the current one or the network reports none
     */
    static String role(String serialCurrent, String serialOne) {
        if (serialCurrent == null || serialOne == null || serialCurrent.equals(serialOne))
            return null;
        return Versions.compare(serialOne, serialCurrent) < 0 ? "legacy" : "successor";
    }


    /**
     * The source list of the publication. The id links to the exact endpoint
     * that was polled, so a reader can check a value against upstream by
     * opening it.
     */
    private static void sourcesTable(StringBuilder sbOut, Map<String, Object> mapDs) {
        sbOut.append("\n## Sources\n\n");
        List<Map<String, Object>> lstSource = maps(mapDs.get("sources"));
        if (lstSource.isEmpty()) {
            sbOut.append("This publication carries no source list.\n");
            return;
        }
        sbOut.append("| Source | Publisher | Authority | Polled every |\n");
        sbOut.append("| --- | --- | --- | --- |\n");
        for (Map<String, Object> mapSource : lstSource) {
            String textId = md(val(mapSource, "id"));
            String urlSource = val(mapSource, "url");
            boolean bLink = urlSource != null && !urlSource.isBlank() && urlSource.chars()
                    .noneMatch(chOne -> chOne == '<' || chOne == '>' || Character.isWhitespace(chOne));
            String textPoll = val(mapSource, "pollSeconds");
            sbOut.append("| ").append(bLink ? "[" + textId + "](<" + urlSource + ">)" : textId)
                    .append(" | ").append(md(val(mapSource, "publisher")))
                    .append(" | ").append(md(val(mapSource, "authority")))
                    .append(" | ").append(textPoll == null ? "-" : md(textPoll) + " s")
                    .append(" |\n");
        }
    }


    /**
     * A value as markdown text. Upstream writes these and nothing here edits
     * them, so every character that would start markup or break a table cell is
     * escaped rather than assumed absent.
     *
     * @param objValue the value, or null
     * @return the escaped text, or a dash for nothing
     */
    static String md(Object objValue) {
        if (objValue == null)
            return "-";
        String textIn = String.valueOf(objValue).replace('\r', ' ').replace('\n', ' ').trim();
        if (textIn.isEmpty())
            return "-";
        StringBuilder sbOut = new StringBuilder(textIn.length() + 8);
        for (int posChar = 0; posChar < textIn.length(); posChar++) {
            char chOne = textIn.charAt(posChar);
            if ("\\`*_[]<>|#&!".indexOf(chOne) >= 0) {
                sbOut.append('\\');
            }
            sbOut.append(chOne);
        }
        return sbOut.toString();
    }


    /** The host part of a url, or the text itself when it does not parse. */
    private static String host(String urlAny) {
        if (urlAny == null)
            return null;
        try {
            String nameHost = URI.create(urlAny.trim()).getHost();
            return nameHost == null ? urlAny : nameHost;
        }
        catch (IllegalArgumentException e) {
            return urlAny;
        }
    }


    private static String nameNetwork(Map<String, Object> mapNetwork) {
        String nameRaw = val(mapNetwork, "network");
        if ("MAINNET".equals(nameRaw))
            return "MainNet";
        if ("TESTNET".equals(nameRaw))
            return "TestNet";
        if ("DEVNET".equals(nameRaw))
            return "DevNet";
        return nameRaw;
    }


    private static List<Map<String, Object>> networks(Map<String, Object> mapDs) {
        return maps(mapDs.get("networks"));
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object objList) {
        List<Map<String, Object>> lstOut = new ArrayList<>();
        if (!(objList instanceof List))
            return lstOut;
        for (Object objOne : (List<?>) objList) {
            if (objOne instanceof Map) {
                lstOut.add((Map<String, Object>) objOne);
            }
        }
        return lstOut;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objSub = mapAny.get(nameField);
        return objSub instanceof Map ? (Map<String, Object>) objSub : null;
    }


    private static String val(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objValue = mapAny.get(nameField);
        return objValue == null ? null : String.valueOf(objValue);
    }


    /**
     * The whole consumer contract, regenerated with every data commit. Three
     * separate things a consumer depends on are stated here because there is
     * nowhere else for them to be stated: the path, the field names, and the
     * meaning of a field. The third is the one that breaks logic without
     * breaking a parser, so it has a section of its own.
     *
     * @param mapDs the corpus being committed
     * @return the text of `REAL_README.md`
     */
    private String contract(Map<String, Object> mapDs) {
        StringBuilder sbOut = new StringBuilder(8192);
        sbOut.append("# Raposza Network Operations Feed - data\n\n");
        sbOut.append("[Back to the data](").append(NAME_README).append(")\n\n");
        sbOut.append("BaseNet can be wired to this feed with a cron job, so that a local\n");
        sbOut.append("customization is tested against what the networks are scheduled to run\n");
        sbOut.append("before it becomes a problem. Pull `").append(NAME_VERSIONS).append("` on a schedule,")
                .append(" compare it\n");
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
        sbOut.append("- `").append(NAME_VERSIONS).append("` - the current state, as the web page and `")
                .append(NAME_README).append("`\n");
        sbOut.append("  show it. Fetch this one.\n");
        sbOut.append("- `").append(NAME_README).append("` - the same data as tables, for a person, with a link\n");
        sbOut.append("  to the web page. Not a contract: its layout may change without notice.\n");
        sbOut.append("- `").append(NAME_CONTRACT).append("` - this file.\n\n");
        sbOut.append("No history file is kept. The commit log of the data branch is the record of\n");
        sbOut.append("every state `").append(NAME_VERSIONS).append("` has held.\n");
        fields(sbOut);
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
        sbOut.append("The full event catalogue, with the source, the observation and the normalizer\n");
        sbOut.append("behind each event, is served by the api; it is not in this repository.\n");
        sbOut.append("\n## Stability\n\n");
        sbOut.append("The Canton networks are still evolving fast. We try our best to keep the\n");
        sbOut.append("interface and the schema stable and coherent, but if something changes\n");
        sbOut.append("upstream we may have to change them too.\n\n");
        sbOut.append("These files are written only from banked observations. An environment that\n");
        sbOut.append("has observed nothing writes nothing at all, so a value here is never a\n");
        sbOut.append("placeholder and an empty field is never an outage.\n");
        sbOut.append("\n## Freshness\n\n");
        sbOut.append("`timestamp` moves on every publication, so it is not what this repository is\n");
        sbOut.append("committed on: a commit is made when one of the values above it changes, or\n");
        sbOut.append("when something `").append(NAME_README).append("` shows changes. No commit means")
                .append(" nothing changed,\n");
        sbOut.append("and the timestamp you read is the one of the last change rather than of the\n");
        sbOut.append("last check. The heartbeat branch is what says the writer is still running.\n");
        sbOut.append("A new release of the service that rewords these two files commits once with\n");
        sbOut.append("no value moved.\n");
        sbOut.append("\n## Branches\n\n");
        sbOut.append("- `").append(spec.nameBranchData()).append("` - the data. One commit per change,")
                .append(" ordinary history, nothing rewritten.\n");
        sbOut.append("- `").append(spec.nameBranchBeat()).append("` - liveness only. A timestamp and the")
                .append(" publisher's uptime in\n");
        sbOut.append("  hours, committed on a fixed interval whether anything changed or not, so\n");
        sbOut.append("  that a silent data branch can be told apart from a writer that has stopped.\n");
        sbOut.append("  It carries no data. Read nothing into its contents beyond the fact that the\n");
        sbOut.append("  writer was alive at that moment.\n");
        return sbOut.toString();
    }


    /**
     * What every field in `versions.yml` means. A consumer that reads a field
     * name and guesses is the failure this section exists to stop, so each one is
     * stated in words rather than left to the name.
     *
     * @param sbOut the README being built
     */
    private void fields(StringBuilder sbOut) {
        sbOut.append("\n## Fields\n\n");
        sbOut.append("Where a description below says what upstream states, it is taken from the\n");
        sbOut.append("Splice release notes; nothing here is inferred from a field name.\n\n");
        sbOut.append("`timestamp` is the RAPOSZA TIMESTAMP: when the file was emitted by the\n");
        sbOut.append("Raposza service. It is not an upstream time, and it is not when any of the\n");
        sbOut.append("values below it changed.\n\n");
        sbOut.append("Under `networks`, per network - the same values, under the same names, as\n");
        sbOut.append("the web page and `").append(NAME_README).append("` show:\n\n");
        sbOut.append("- `current` - the last CONFIRMED upgrade on the SV Operations Schedule whose\n");
        sbOut.append("  date has arrived, as `version` and `since`. The schedule states what the\n");
        sbOut.append("  operators intend, so a date that has arrived is not proof the upgrade ran;\n");
        sbOut.append("  `svs-on-current` and `reported` are what the network says.\n");
        sbOut.append("- `svs-on-current` - how many Super Validator nodes report `current` in their\n");
        sbOut.append("  own roster entry, as `count` `of` all nodes. `count` is `null` where there is\n");
        sbOut.append("  no `current`.\n");
        sbOut.append("- `minimum` - the oldest version a validator may run, from the schedule.\n");
        sbOut.append("  Upstream states it in prose and often to a minor version only, so `\"0.7\"`\n");
        sbOut.append("  here means `0.7.x` and no patch digit is invented.\n");
        sbOut.append("- `next` - the earliest upgrade still ahead, as `version`, `date` and `status`\n");
        sbOut.append("  (`CONFIRMED` or `TENTATIVE`), all `null` where none is scheduled. An entry\n");
        sbOut.append("  upstream has cancelled is not published here.\n");
        sbOut.append("- `reported` - the version the network's own info document states. It can lag\n");
        sbOut.append("  the network, so it can differ from `current` in either direction.\n");
        sbOut.append("- `serial-id` - upstream increments it by one for each logical synchronizer\n");
        sbOut.append("  upgrade, and states that it carries what the migration id used to: release\n");
        sbOut.append("  names, DNS entries, database names, chain ids and port numbers. If you pin\n");
        sbOut.append("  any of those, this is the number they follow.\n");
        sbOut.append("- `migration-id` - upstream states this is now FROZEN at its current value and\n");
        sbOut.append("  configured once, and that operators keep the value they have. It is\n");
        sbOut.append("  published because a frozen field that moves would be worth knowing about.\n");
        sbOut.append("- `successor` and `legacy` - the versions of the synchronizer being upgraded\n");
        sbOut.append("  to and from. Upstream supports the two coexisting during an upgrade rather\n");
        sbOut.append("  than cutting over, so both are `null` while none is in flight, and a\n");
        sbOut.append("  non-null `successor` is the network reporting one that is.\n");
        sbOut.append("- `super-validators` - every Super Validator node of the network with the\n");
        sbOut.append("  version it reports, the scan url it answers on, and its `sequencers`: one\n");
        sbOut.append("  per synchronizer serial, highest first, each with `serial`, `url` and\n");
        sbOut.append("  `role` - `legacy` below the network's `serial-id`, `successor` above it,\n");
        sbOut.append("  `null` on it. YOU ARE CONNECTED TO A NODE, NOT TO A NETWORK: a roster\n");
        sbOut.append("  carrying more than one version is normal during an upgrade. A node upstream\n");
        sbOut.append("  could not read at the last poll keeps the version last read. Lists are\n");
        sbOut.append("  empty where nothing is reported, never absent.\n\n");
        sbOut.append("`splice-latest` is the highest version the Splice tags endpoint carries. It is\n");
        sbOut.append("different in kind from everything above: it says a release EXISTS, and says\n");
        sbOut.append("nothing about any network taking it. A tag carries no date, so this value has\n");
        sbOut.append("none.\n\n");
        sbOut.append("Every version is a QUOTED STRING. Unquoted, `0.7` is a number to every yaml\n");
        sbOut.append("parser there is. `timestamp`, `since` and `date` are left unquoted so that they\n");
        sbOut.append("load as a timestamp and dates, `status` and `role` as plain words, and\n");
        sbOut.append("`migration-id`, `serial-id`, `serial` and the counts as whole numbers.\n");
    }


    /**
     * The source list of the publication itself, so the cadences stated here are
     * the ones the build actually runs on and cannot drift from the registry.
     * The url is rendered as a link: a reader who wants to check a value against
     * upstream must be able to open the exact endpoint that was polled, and a
     * host name alone does not say which one that is.
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
                Object objUrl = mapOne.get("url");
                String textUrl = objUrl == null ? "" : String.valueOf(objUrl).trim();
                sbOut.append("- `").append(mapOne.get("id")).append("` - ").append(mapOne.get("publisher"))
                        .append(", authority ").append(mapOne.get("authority"))
                        .append(", polled every ").append(mapOne.get("pollSeconds")).append(" s");
                if (textUrl.isEmpty()) {
                    sbOut.append(", url not published with this source\n");
                }
                else {
                    sbOut.append("\n  [").append(textUrl).append("](").append(textUrl).append(")\n");
                }
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


    private record Exec(int code, byte[] bytesOut, String textErr) {
    }
}
