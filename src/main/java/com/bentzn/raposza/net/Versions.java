/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * `versions.yml`: the published state reduced to the values a consumer acts on,
 * in a form a person also reads without tooling.
 *
 * IT CARRIES WHAT THE WEB PAGE AND THE README SHOW, UNDER THE SAME NAMES, so a
 * reader who looked at either finds the same values here. Per network, in the
 * page's order: `current`, the last confirmed upgrade on the schedule whose date
 * has arrived, with `since`; `svs-on-current`, how many Super Validator nodes
 * report that version; `minimum`; `next`, the earliest upgrade still ahead; and
 * `reported`, what the network's own info document states. Then the identity
 * of the deployment, and every node with its version, scan url and sequencers,
 * each sequencer marked legacy or successor against the reported serial as the
 * page marks it. The last line is the highest version the Splice tags endpoint
 * carries, which is the only source that says a release exists at all.
 *
 * A node upstream could not read at the last roster carries the version last
 * read and nothing else: marking it would commit every failed read and its
 * recovery, and this file commits only when a network moves.
 *
 * EVERY VERSION IS A QUOTED STRING. Unquoted, `0.7` is a number to every yaml
 * parser there is, and `0.7.0` is not the same value as `0.7` once one of them
 * has been through a float. The timestamp and the scheduled date are left
 * unquoted, because a timestamp and a date are what they are meant to load as.
 *
 * Versions are compared segment by segment as numbers. Compared as text, 0.10.4
 * sorts below 0.8.2 and the file would quietly state the wrong latest.
 *
 * Author Claude/bentzn
 */
public final class Versions {

    /** The networks, in the order they are published. */
    private static final List<String> LST_NETWORK = List.of("MAINNET", "TESTNET", "DEVNET");

    /** The key each network is published under. */
    private static final Map<String, String> MAP_KEY =
            Map.of("MAINNET", "mainnet", "TESTNET", "testnet", "DEVNET", "devnet");

    private static final String KIND_RELEASE = "SOFTWARE_RELEASE";

    /** The one field that moves on every publication whether anything happened or not. */
    private static final String KEY_STAMP = "timestamp:";


    private Versions() {
    }


    /**
     * @param mapDs the corpus that was just published
     * @return the whole text of `versions.yml`, newline terminated
     */
    public static String yaml(Map<String, Object> mapDs) {
        StringBuilder sbOut = new StringBuilder(8192);
        sbOut.append(KEY_STAMP).append(' ')
                .append(String.valueOf(Dataset.meta(mapDs).get("createdAt"))).append('\n');
        sbOut.append("networks:\n");
        for (String nameNetwork : LST_NETWORK) {
            Map<String, Object> mapNetwork = network(mapDs, nameNetwork);
            Map<String, Object> mapSplice = sub(mapNetwork, "splice");
            Map<String, Object> mapNext = sub(mapNetwork, "next");
            Map<String, Object> mapDeploy = sub(mapNetwork, "deployment");
            Map<String, Object> mapRoster = sub(mapNetwork, "roster");
            String verCurrent = field(mapSplice, "scheduledVersion");
            sbOut.append("  ").append(MAP_KEY.get(nameNetwork)).append(":\n");
            sbOut.append("    current:\n");
            sbOut.append("      version: ").append(quote(verCurrent)).append('\n');
            sbOut.append("      since: ").append(plain(field(mapSplice, "scheduledFrom"))).append('\n');
            sbOut.append("    svs-on-current:\n");
            sbOut.append("      count: ").append(number(verCurrent == null ? null : field(mapRoster, "onScheduled")))
                    .append('\n');
            sbOut.append("      of: ").append(number(field(mapRoster, "nodes"))).append('\n');
            sbOut.append("    minimum: ").append(quote(field(mapSplice, "minimumVersion"))).append('\n');
            sbOut.append("    next:\n");
            sbOut.append("      version: ").append(quote(field(mapNext, "version"))).append('\n');
            sbOut.append("      date: ").append(plain(field(mapNext, "from"))).append('\n');
            sbOut.append("      status: ").append(plain(field(mapNext, "status"))).append('\n');
            sbOut.append("    reported: ").append(quote(field(mapDeploy, "version"))).append('\n');
            sbOut.append("    migration-id: ").append(number(field(mapDeploy, "migrationId"))).append('\n');
            sbOut.append("    serial-id: ").append(number(field(mapDeploy, "serialId"))).append('\n');
            sbOut.append("    successor: ").append(quote(field(mapDeploy, "successorVersion"))).append('\n');
            sbOut.append("    legacy: ").append(quote(field(mapDeploy, "legacyVersion"))).append('\n');
            nodes(sbOut, mapNetwork, field(mapDeploy, "serialId"));
        }
        sbOut.append("splice-latest: ").append(quote(latest(mapDs))).append('\n');
        return sbOut.toString();
    }


    /**
     * The Super Validator roster of one network, in the order the publication
     * holds it, each node with its sequencers, highest serial first.
     *
     * An empty roster is written as an empty sequence rather than omitted: a key
     * that disappears when a network reports no node makes a consumer's lookup
     * throw on a condition that is not an error. The same holds for a node's
     * sequencers.
     *
     * @param sbOut the file being written
     * @param mapNetwork one network record of the publication
     * @param serialCurrent the serial the network reports, which decides the role
     *        of each sequencer
     */
    private static void nodes(StringBuilder sbOut, Map<String, Object> mapNetwork, String serialCurrent) {
        Object objList = mapNetwork == null ? null : mapNetwork.get("superValidators");
        if (!(objList instanceof List) || ((List<?>) objList).isEmpty()) {
            sbOut.append("    super-validators: []\n");
            return;
        }
        sbOut.append("    super-validators:\n");
        for (Object objNode : (List<?>) objList) {
            if (!(objNode instanceof Map))
                continue;
            Map<String, Object> mapNode = cast(objNode);
            sbOut.append("      - name: ").append(quote(field(mapNode, "name"))).append('\n');
            sbOut.append("        version: ").append(quote(field(mapNode, "version"))).append('\n');
            sbOut.append("        scan: ").append(quote(field(mapNode, "scan"))).append('\n');
            Object objSeq = mapNode.get("sequencers");
            if (!(objSeq instanceof List) || ((List<?>) objSeq).isEmpty()) {
                sbOut.append("        sequencers: []\n");
                continue;
            }
            sbOut.append("        sequencers:\n");
            for (Object objOne : (List<?>) objSeq) {
                if (!(objOne instanceof Map))
                    continue;
                Map<String, Object> mapSeq = cast(objOne);
                String serialOne = field(mapSeq, "serial");
                sbOut.append("          - serial: ").append(number(serialOne)).append('\n');
                sbOut.append("            url: ").append(quote(field(mapSeq, "url"))).append('\n');
                sbOut.append("            role: ").append(plain(GitPublish.role(serialCurrent, serialOne))).append('\n');
            }
        }
    }


    /**
     * The change unit. The timestamp moves on every publication by design, so a
     * comparison that counted it would commit on every turn and say nothing;
     * everything else in the file is a fact about a network.
     *
     * @param textYaml the text of a `versions.yml`
     * @return it without the timestamp line
     */
    public static String withoutTimestamp(String textYaml) {
        StringBuilder sbOut = new StringBuilder(textYaml.length());
        for (String lineOne : textYaml.split("\n")) {
            if (lineOne.startsWith(KEY_STAMP))
                continue;
            sbOut.append(lineOne).append('\n');
        }
        return sbOut.toString();
    }


    /**
     * @param verLeft a dotted version
     * @param verRight another
     * @return negative, zero or positive as left orders below, with, or above
     *         right; a segment that is not a number counts as zero
     */
    public static int compare(String verLeft, String verRight) {
        String[] arrLeft = verLeft.split("\\.");
        String[] arrRight = verRight.split("\\.");
        int cntPart = Math.max(arrLeft.length, arrRight.length);
        for (int posPart = 0; posPart < cntPart; posPart++) {
            int nLeft = part(arrLeft, posPart);
            int nRight = part(arrRight, posPart);
            if (nLeft != nRight)
                return nLeft < nRight ? -1 : 1;
        }
        return 0;
    }


    /**
     * The highest version among the releases the tags source states exist.
     *
     * @param mapDs the corpus
     * @return the version, or null where nothing states one
     */
    public static String latest(Map<String, Object> mapDs) {
        Map<?, ?> mapBest = latestEvent(mapDs);
        return mapBest == null ? null : String.valueOf(((Map<?, ?>) mapBest.get("version")).get("value"));
    }


    /**
     * @param mapDs the corpus
     * @return the published event of the highest release, or null where nothing
     *         states one; it carries the provenance of the version latest returns
     */
    public static Map<?, ?> latestEvent(Map<String, Object> mapDs) {
        Map<?, ?> mapBest = null;
        String verBest = null;
        Object objList = mapDs.get("events");
        if (!(objList instanceof List))
            return null;
        for (Object objEvent : (List<?>) objList) {
            if (!(objEvent instanceof Map))
                continue;
            Map<?, ?> mapEvent = (Map<?, ?>) objEvent;
            if (!KIND_RELEASE.equals(String.valueOf(mapEvent.get("kind"))))
                continue;
            if ("true".equals(String.valueOf(mapEvent.get("withdrawn"))))
                continue;
            Object objVersion = mapEvent.get("version");
            if (!(objVersion instanceof Map))
                continue;
            Object objValue = ((Map<?, ?>) objVersion).get("value");
            if (objValue == null)
                continue;
            String verOne = String.valueOf(objValue);
            if (verBest == null || compare(verOne, verBest) > 0) {
                verBest = verOne;
                mapBest = mapEvent;
            }
        }
        return mapBest;
    }


    /**
     * A version as a yaml string, or the null literal. Upstream writes the value
     * and nothing here edits it, so the two characters that would break the
     * quoting are escaped rather than assumed absent.
     *
     * @param textValue the value, or null
     * @return the scalar to write
     */
    private static String plain(String textValue) {
        return textValue == null ? "null" : textValue;
    }


    /**
     * @param textValue a value upstream states as a whole number, or null
     * @return it unquoted, so it loads as a number; anything that is not digits
     *         is quoted rather than allowed to load as something else
     */
    private static String number(String textValue) {
        if (textValue == null)
            return "null";
        return textValue.matches("[0-9]+") ? textValue : quote(textValue);
    }


    /**
     * A double-quoted scalar. A node's name and its urls are text a third party
     * wrote, so a line break in one would fold into the value or end the scalar
     * where the file's indentation no longer allows it, and a control character
     * is one a yaml loader refuses outright. Every character below space, DEL,
     * the C1 controls, the two Unicode line and paragraph separators and the two
     * noncharacters at the top of the basic plane are therefore written as a
     * four-digit escape, beside the backslash and the quote.
     *
     * @param textValue any text, or null
     * @return it as one quoted scalar on one line, or null unquoted
     */
    private static String quote(String textValue) {
        if (textValue == null)
            return "null";
        StringBuilder sbOut = new StringBuilder(textValue.length() + 2);
        sbOut.append('"');
        for (int posChar = 0; posChar < textValue.length(); posChar++) {
            char chOne = textValue.charAt(posChar);
            if (chOne == '\\' || chOne == '"') {
                sbOut.append('\\').append(chOne);
            }
            else if (chOne < 0x20 || (chOne >= 0x7f && chOne < 0xa0) || chOne == 0x2028 || chOne == 0x2029
                    || chOne == 0xfffe || chOne == 0xffff) {
                sbOut.append(String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(chOne)));
            }
            else {
                sbOut.append(chOne);
            }
        }
        return sbOut.append('"').toString();
    }


    private static Map<String, Object> network(Map<String, Object> mapDs, String nameNetwork) {
        Object objList = mapDs.get("networks");
        if (!(objList instanceof List))
            return null;
        for (Object objNetwork : (List<?>) objList) {
            if (!(objNetwork instanceof Map))
                continue;
            Map<String, Object> mapNetwork = cast(objNetwork);
            if (nameNetwork.equals(String.valueOf(mapNetwork.get("network"))))
                return mapNetwork;
        }
        return null;
    }


    private static Map<String, Object> sub(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objSub = mapAny.get(nameField);
        return objSub instanceof Map ? cast(objSub) : null;
    }


    private static int part(String[] arrPart, int posPart) {
        if (posPart >= arrPart.length)
            return 0;
        try {
            return Integer.parseInt(arrPart[posPart]);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }


    private static String field(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objValue = mapAny.get(nameField);
        return objValue == null ? null : String.valueOf(objValue);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
