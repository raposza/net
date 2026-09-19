/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.List;
import java.util.Map;

/**
 * `versions.yml`: the published state reduced to the values a consumer acts on,
 * in a form a person also reads without tooling.
 *
 * Per network it carries the version that is scheduled to be running, the
 * minimum version in force, and the next upgrade still ahead. The last line is
 * the highest version the Splice tags endpoint carries, which is the only source
 * that says a release exists at all.
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
        StringBuilder sbOut = new StringBuilder(512);
        sbOut.append(KEY_STAMP).append(' ')
                .append(String.valueOf(Dataset.meta(mapDs).get("createdAt"))).append('\n');
        sbOut.append("networks:\n");
        for (String nameNetwork : LST_NETWORK) {
            Map<String, Object> mapNetwork = network(mapDs, nameNetwork);
            Map<String, Object> mapSplice = sub(mapNetwork, "splice");
            Map<String, Object> mapNext = sub(mapNetwork, "next");
            String verNext = field(mapNext, "version");
            String dayNext = field(mapNext, "from");
            sbOut.append("  ").append(MAP_KEY.get(nameNetwork)).append(":\n");
            sbOut.append("    current: ").append(quote(field(mapSplice, "scheduledVersion"))).append('\n');
            sbOut.append("    minimum: ").append(quote(field(mapSplice, "minimumVersion"))).append('\n');
            if (verNext == null && dayNext == null) {
                sbOut.append("    scheduled: null\n");
            }
            else {
                sbOut.append("    scheduled:\n");
                sbOut.append("      date: ").append(dayNext == null ? "null" : dayNext).append('\n');
                sbOut.append("      version: ").append(quote(verNext)).append('\n');
            }
        }
        sbOut.append("splice-latest: ").append(quote(latest(mapDs))).append('\n');
        return sbOut.toString();
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
            }
        }
        return verBest;
    }


    /**
     * A version as a yaml string, or the null literal. Upstream writes the value
     * and nothing here edits it, so the two characters that would break the
     * quoting are escaped rather than assumed absent.
     *
     * @param textValue the value, or null
     * @return the scalar to write
     */
    private static String quote(String textValue) {
        if (textValue == null)
            return "null";
        return "\"" + textValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
