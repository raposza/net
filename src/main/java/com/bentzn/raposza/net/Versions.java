/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.List;
import java.util.Map;

/**
 * The published dataset as four lines a person can read.
 *
 * It states nothing `feed.json` does not. Per network it carries the latest
 * SCHEDULED version whose date has arrived and the minimum version in force,
 * both from the schedule; the last line is the highest version the Splice tags
 * endpoint carries, which is the only source that says a release EXISTS.
 * Whether any network runs it is the question the three lines above answer, and
 * they answer it with an intention rather than an observation.
 *
 * Versions are compared segment by segment as numbers. Compared as text, 0.10.4
 * sorts below 0.8.2 and the file would quietly state the wrong latest.
 *
 * A publication whose content is not OBSERVED is marked as such on a first
 * line, because four lines of dashes with no explanation read as an outage.
 *
 * Author Claude/bentzn
 */
public final class Versions {

    /** The networks, in the order a reader asked for them. */
    private static final List<String> LST_NETWORK = List.of("MAINNET", "TESTNET", "DEVNET");

    private static final Map<String, String> MAP_LABEL =
            Map.of("MAINNET", "MainNet", "TESTNET", "TestNet", "DEVNET", "DevNet");

    private static final String KIND_RELEASE = "SOFTWARE_RELEASE";


    private Versions() {
    }


    /**
     * @param mapDs the corpus
     * @return the text of the human-readable file, newline terminated
     */
    public static String text(Map<String, Object> mapDs) {
        StringBuilder sbOut = new StringBuilder(256);
        String nameContent = String.valueOf(Dataset.meta(mapDs).get("content"));
        if (!"OBSERVED".equals(nameContent)) {
            sbOut.append("# no observed data in this publication: metadata.content is ")
                    .append(nameContent).append("\n");
        }
        for (String nameNetwork : LST_NETWORK) {
            Map<String, Object> mapSplice = splice(mapDs, nameNetwork);
            sbOut.append(MAP_LABEL.get(nameNetwork)).append(": ")
                    .append(or(field(mapSplice, "scheduledVersion")))
                    .append(" (min: ").append(or(field(mapSplice, "minimumVersion"))).append(")\n");
        }
        sbOut.append("Splice latest: ").append(or(latest(mapDs))).append("\n");
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


    /** The highest version among the releases the tags source states exist. */
    private static String latest(Map<String, Object> mapDs) {
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


    private static Map<String, Object> splice(Map<String, Object> mapDs, String nameNetwork) {
        Object objList = mapDs.get("networks");
        if (!(objList instanceof List))
            return null;
        for (Object objNetwork : (List<?>) objList) {
            if (!(objNetwork instanceof Map))
                continue;
            Map<?, ?> mapNetwork = (Map<?, ?>) objNetwork;
            if (nameNetwork.equals(String.valueOf(mapNetwork.get("network")))) {
                Object objSplice = mapNetwork.get("splice");
                return objSplice instanceof Map ? cast(objSplice) : null;
            }
        }
        return null;
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


    private static String or(String textValue) {
        return textValue == null ? "-" : textValue;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
