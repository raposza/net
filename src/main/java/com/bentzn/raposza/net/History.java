/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * `history.yml`: one entry per change to `versions.yml`, newest first, each a
 * full snapshot of the values it held.
 *
 * The accumulated record is the thing this project has that a reader cannot
 * rebuild from anywhere else, so it is published as a file rather than left to
 * be reconstructed from a commit log. A consumer that wants only the current
 * state fetches `versions.yml` and never reads this at all.
 *
 * A new entry is SPLICED IN under the opening line rather than the file being
 * reparsed and rewritten. Nothing but this class ever writes the file, so its
 * shape is known exactly; a parser would be a second definition of that shape
 * and would silently reformat every older entry the day the emitter changed.
 * What that costs is a file whose opening line has to be what is expected, and
 * a file that is not is REFUSED rather than replaced: an unreadable history is
 * recoverable by hand, an overwritten one is not.
 *
 * Author Claude/bentzn
 */
public final class History {

    /** The opening line. Every entry is a list item under it. */
    public static final String LINE_HEADER = "history:";


    private History() {
    }


    /**
     * @param fileVersions where `versions.yml` currently is
     * @param textNew the text that would be written
     * @return true when the file on disk states the same facts, its timestamp
     *         aside
     */
    public static boolean same(Path fileVersions, String textNew) {
        if (!Files.isRegularFile(fileVersions))
            return false;
        try {
            return Versions.withoutTimestamp(Files.readString(fileVersions, StandardCharsets.UTF_8))
                    .equals(Versions.withoutTimestamp(textNew));
        }
        catch (IOException e) {
            return false;
        }
    }


    /**
     * The whole new text of `history.yml`, with this publication's snapshot as
     * its first entry.
     *
     * @param fileHistory where the file is, which need not exist yet
     * @param textVersions the text of the `versions.yml` being written
     * @return the text to write
     * @throws IOException when the existing file cannot be read, or does not open
     *         with the line this class wrote
     */
    public static String prepend(Path fileHistory, String textVersions) throws IOException {
        String textEntry = entry(textVersions);
        if (!Files.isRegularFile(fileHistory))
            return LINE_HEADER + "\n" + textEntry;
        String textOld = Files.readString(fileHistory, StandardCharsets.UTF_8);
        int posBreak = textOld.indexOf('\n');
        String lineFirst = posBreak < 0 ? textOld : textOld.substring(0, posBreak);
        if (!LINE_HEADER.equals(lineFirst.trim())) {
            throw new IOException(fileHistory + " does not open with '" + LINE_HEADER
                    + "': refusing to rewrite it");
        }
        String textRest = posBreak < 0 ? "" : textOld.substring(posBreak + 1);
        return LINE_HEADER + "\n" + textEntry + textRest;
    }


    /**
     * One list item carrying the whole of a `versions.yml`. The emitter is the
     * one in Versions, re-indented, so the two files cannot state a field
     * differently.
     *
     * @param textVersions the text of a `versions.yml`
     * @return the entry, newline terminated
     */
    static String entry(String textVersions) {
        StringBuilder sbOut = new StringBuilder(textVersions.length() + 64);
        boolean bFirst = true;
        for (String lineOne : textVersions.split("\n")) {
            if (lineOne.isEmpty()) {
                continue;
            }
            sbOut.append(bFirst ? "  - " : "    ").append(lineOne).append('\n');
            bFirst = false;
        }
        return sbOut.toString();
    }
}
