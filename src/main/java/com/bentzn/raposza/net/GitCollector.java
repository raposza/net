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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The git transport. A git-backed source is collected as commits, not as a
 * polled document: the provenance is exact, no rendered page is diffed, and the
 * whole history is available the first time the collector runs instead of
 * accumulating over weeks.
 *
 * One commit on the first-parent line is one observation. A merge contributes
 * the state the branch was actually in, not the states of the branch merged
 * into it.
 *
 * The retrieved representation of a commit is a MANIFEST: the sorted list of
 * tracked paths with the sha256 of each file. The manifest is canonical, so two
 * commits with an identical tree produce identical bytes and the evidence store
 * holds one copy; each file body is stored under its own content address beside
 * it, so a normalizer reads the file it wants without a working copy.
 *
 * The collector shells out to git rather than embedding an implementation of
 * it. The transport behaviour is then exactly the one the operator can
 * reproduce by hand on the same host.
 *
 * Author Claude/bentzn
 */
public final class GitCollector {

    /** Recorded on every observation; change it when the manifest shape changes. */
    public static final String VER_COLLECTOR = "GitCollector@1.0";

    /** Media type of the manifest, which is what an observation of a commit is. */
    public static final String MEDIA_MANIFEST = "application/vnd.raposza.git-manifest+json";

    private static final long SEC_TIMEOUT = 900L;

    private static final DateTimeFormatter FMT_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);


    private GitCollector() {
    }


    /**
     * Polls one git-backed source.
     *
     * Never throws: a transport failure is a recorded outcome, because a source
     * that is down is data about the source and not a failure of the caller.
     *
     * @param def the source definition
     * @param dirMirror directory holding one bare mirror per source
     * @param dirEvidence root of the evidence store
     * @param revLastSeen the newest revision already banked, or null to bootstrap
     * @return what happened, and the observations produced oldest first
     */
    public static Poll collect(SourceDef def, Path dirMirror, Path dirEvidence, String revLastSeen) {
        long msStart = System.currentTimeMillis();
        try {
            Path dirRepo = mirror(def, dirMirror);
            String revHead = text(must(dirRepo, "git", "rev-parse", def.ref())).trim();
            List<String> lstRev = pending(dirRepo, revLastSeen, revHead);
            if (lstRev.isEmpty())
                return Poll.of("UNCHANGED", "head " + shortRev(revHead), millis(msStart), revHead);
            List<Banked> lstOut = new ArrayList<>();
            Map<String, String> mapBlob = new HashMap<>();
            for (String revCommit : lstRev) {
                lstOut.add(bank(def, dirRepo, dirEvidence, revCommit, mapBlob));
            }
            return new Poll("CHANGED", lstOut.size() + " commits to " + shortRev(revHead),
                    millis(msStart), revHead, lstOut);
        }
        catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Poll.of("TRANSPORT_ERROR", String.valueOf(e.getMessage()), millis(msStart), null);
        }
    }


    private static Path mirror(SourceDef def, Path dirMirror) throws IOException, InterruptedException {
        Path dirRepo = dirMirror.resolve(def.id() + ".git");
        if (Files.isDirectory(dirRepo.resolve("objects"))) {
            must(dirRepo, "git", "remote", "update", "--prune");
            return dirRepo;
        }
        Files.createDirectories(dirMirror);
        must(dirMirror, "git", "clone", "--mirror", "--quiet", def.url(), dirRepo.toString());
        return dirRepo;
    }


    private static List<String> pending(Path dirRepo, String revLastSeen, String revHead)
            throws IOException, InterruptedException {
        boolean hasBase = revLastSeen != null && !revLastSeen.isBlank()
                && exec(dirRepo, "git", "cat-file", "-e", revLastSeen + "^{commit}").code() == 0;
        String[] argsRev = hasBase
                ? new String[] { "git", "rev-list", "--reverse", "--first-parent", revLastSeen + ".." + revHead }
                : new String[] { "git", "rev-list", "--reverse", "--first-parent", revHead };
        List<String> lstRev = new ArrayList<>();
        for (String lineRev : text(must(dirRepo, argsRev)).split("\n")) {
            if (!lineRev.isBlank()) {
                lstRev.add(lineRev.trim());
            }
        }
        return lstRev;
    }


    private static Banked bank(SourceDef def, Path dirRepo, Path dirEvidence, String revCommit,
            Map<String, String> mapBlob) throws IOException, InterruptedException {
        String stampCommitted = stamp(text(must(dirRepo, "git", "show", "-s", "--format=%cI", revCommit)).trim());
        List<String[]> lstEntry = tree(dirRepo, revCommit, def.pathPrefix());
        StringBuilder sbManifest = new StringBuilder(4096);
        sbManifest.append("{\"sourceId\":").append(quote(def.id()));
        sbManifest.append(",\"revision\":").append(quote(revCommit));
        sbManifest.append(",\"committedAt\":").append(quote(stampCommitted));
        sbManifest.append(",\"pathPrefix\":").append(def.pathPrefix() == null ? "null" : quote(def.pathPrefix()));
        sbManifest.append(",\"collector\":").append(quote(VER_COLLECTOR));
        sbManifest.append(",\"files\":[");
        for (int cntEntry = 0; cntEntry < lstEntry.size(); cntEntry++) {
            String[] arrEntry = lstEntry.get(cntEntry);
            String keyBlob = mapBlob.get(arrEntry[0]);
            if (keyBlob == null) {
                keyBlob = Evidence.put(dirEvidence, must(dirRepo, "git", "cat-file", "blob", arrEntry[0]).bytesOut());
                mapBlob.put(arrEntry[0], keyBlob);
            }
            if (cntEntry > 0) {
                sbManifest.append(',');
            }
            sbManifest.append("{\"path\":").append(quote(arrEntry[1]));
            sbManifest.append(",\"key\":").append(quote(keyBlob)).append('}');
        }
        sbManifest.append("]}");
        byte[] bytesManifest = sbManifest.toString().getBytes(StandardCharsets.UTF_8);
        String keyStorage = Evidence.put(dirEvidence, bytesManifest);
        return new Banked(keyStorage, keyStorage.substring("sha256/".length()), MEDIA_MANIFEST,
                revCommit, stampCommitted, null, null, null);
    }


    /**
     * Reads the tree of one commit, NUL-separated so a path holding a space or a
     * quote arrives as the bytes git holds rather than as git's display form.
     *
     * @return blob sha and path, in git's own sort order, blobs only
     */
    private static List<String[]> tree(Path dirRepo, String revCommit, String pathPrefix)
            throws IOException, InterruptedException {
        List<String> lstArg = new ArrayList<>(List.of("git", "ls-tree", "-r", "-z", revCommit));
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            lstArg.add("--");
            lstArg.add(pathPrefix);
        }
        List<String[]> lstEntry = new ArrayList<>();
        for (String recEntry : text(must(dirRepo, lstArg.toArray(new String[0]))).split("\u0000")) {
            if (recEntry.isEmpty()) {
                continue;
            }
            int posTab = recEntry.indexOf('\t');
            if (posTab < 0) {
                continue;
            }
            String[] arrMeta = recEntry.substring(0, posTab).split(" ");
            if (arrMeta.length < 3 || !"blob".equals(arrMeta[1])) {
                continue;
            }
            lstEntry.add(new String[] { arrMeta[2], recEntry.substring(posTab + 1) });
        }
        return lstEntry;
    }


    private static String stamp(String stampGit) {
        return FMT_ISO.format(OffsetDateTime.parse(stampGit).toInstant());
    }


    private static String shortRev(String revFull) {
        return revFull == null || revFull.length() < 12 ? String.valueOf(revFull) : revFull.substring(0, 12);
    }


    private static long millis(long msStart) {
        return System.currentTimeMillis() - msStart;
    }


    private static String text(Exec exec) {
        return new String(exec.bytesOut(), StandardCharsets.UTF_8);
    }


    private static Exec must(Path dirWork, String... argsCmd) throws IOException, InterruptedException {
        Exec exec = exec(dirWork, argsCmd);
        if (exec.code() != 0)
            throw new IOException(String.join(" ", argsCmd) + " exited " + exec.code() + ": " + exec.textErr().trim());
        return exec;
    }


    private static Exec exec(Path dirWork, String... argsCmd) throws IOException, InterruptedException {
        ProcessBuilder bldProc = new ProcessBuilder(argsCmd);
        bldProc.directory(dirWork.toFile());
        bldProc.environment().put("GIT_TERMINAL_PROMPT", "0");
        bldProc.environment().put("GIT_ASKPASS", "true");
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


    private static byte[] drain(InputStream strmIn) throws IOException {
        ByteArrayOutputStream strmOut = new ByteArrayOutputStream();
        byte[] bufRead = new byte[65536];
        int cntRead;
        while ((cntRead = strmIn.read(bufRead)) >= 0) {
            strmOut.write(bufRead, 0, cntRead);
        }
        return strmOut.toByteArray();
    }


    private static String quote(String textRaw) {
        StringBuilder sbOut = new StringBuilder(textRaw.length() + 2);
        sbOut.append('"');
        for (int cntChar = 0; cntChar < textRaw.length(); cntChar++) {
            char chOne = textRaw.charAt(cntChar);
            switch (chOne) {
                case '"':
                    sbOut.append("\\\"");
                    break;
                case '\\':
                    sbOut.append("\\\\");
                    break;
                case '\n':
                    sbOut.append("\\n");
                    break;
                case '\r':
                    sbOut.append("\\r");
                    break;
                case '\t':
                    sbOut.append("\\t");
                    break;
                default:
                    if (chOne < 0x20) {
                        sbOut.append(String.format("\\u%04x", (int) chOne));
                    }
                    else {
                        sbOut.append(chOne);
                    }
            }
        }
        return sbOut.append('"').toString();
    }


    private record Exec(int code, byte[] bytesOut, String textErr) {
    }
}
