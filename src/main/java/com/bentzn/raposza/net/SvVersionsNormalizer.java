/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one network's Super Validator roster into claims, one record per node.
 *
 * The network-wide version is a single number and an organization does not talk
 * to a network, it talks to a node. Those are not the same fact: at the time
 * this was written DevNet reported one version for the synchronizer while its
 * roster carried two, so a consumer told only the network figure would have been
 * given something true about the network and useless about its own connection.
 *
 * The body is comma-separated text with a header row, served as text/plain, so
 * the media type says nothing and the SHAPE is the only check there is. The
 * header is required to be the one this parser was written against, and every
 * data row is required to hold exactly three fields. A body that fails either is
 * refused whole: an HTML error page, a truncated response and a changed column
 * order all arrive as text/plain and would otherwise be read as a roster.
 *
 * Identity is the SV NAME. It is upstream's own reference, it is what the
 * Foundation's approved identity file uses, and it is what a reader would cite.
 * The scan url is carried beside it because the name is a label and the url is
 * how an organization recognises the node it is actually connected to.
 *
 * Rows are sorted by name, so a body upstream merely re-ordered produces the
 * same claims and makes no revision.
 *
 * Author Claude/bentzn
 */
public final class SvVersionsNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "SvVersionsNormalizer@1";

    /** The header the body must open with, fields trimmed. */
    public static final List<String> LST_HEADER = List.of("SV Name", "Scan URL", "Version");

    private static final String KIND = "SV_NODE";

    private static final String TYPE_NODE = "sv-node";

    private static final String HIGH = "HIGH";

    private static final String REVIEW = "REQUIRES_REVIEW";

    private static final Pattern PAT_PATCH = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)");

    private static final Pattern PAT_MINOR = Pattern.compile("([0-9]+)\\.([0-9]+)");

    private final String idSource;

    private final String nameNetwork;


    /**
     * @param idSourceUse the source this instance reads
     * @param nameNetworkUse the network as this project names it, MAINNET,
     *        TESTNET or DEVNET
     */
    public SvVersionsNormalizer(String idSourceUse, String nameNetworkUse) {
        this.idSource = idSourceUse;
        this.nameNetwork = nameNetworkUse;
    }


    @Override
    public String id() {
        return ID;
    }


    @Override
    public String sourceId() {
        return idSource;
    }


    @Override
    public List<Claim> normalize(byte[] bytesBody) throws Refused {
        String textBody = new String(bytesBody, StandardCharsets.UTF_8);
        List<String> lstLine = new ArrayList<>();
        for (String lineOne : textBody.split("\n")) {
            if (!lineOne.trim().isEmpty()) {
                lstLine.add(lineOne);
            }
        }
        if (lstLine.isEmpty())
            throw new Refused("the body is empty");
        List<String> lstHead = fields(lstLine.get(0));
        if (!LST_HEADER.equals(lstHead))
            throw new Refused("the header is " + lstHead + " and not " + LST_HEADER);
        if (lstLine.size() < 2)
            throw new Refused("the body carries a header and no node");

        Set<String> setName = new HashSet<>();
        List<Claim> lstOut = new ArrayList<>();
        for (int posLine = 1; posLine < lstLine.size(); posLine++) {
            List<String> lstField = fields(lstLine.get(posLine));
            if (lstField.size() != LST_HEADER.size()) {
                throw new Refused("row " + posLine + " holds " + lstField.size() + " fields and not "
                        + LST_HEADER.size());
            }
            String nameSv = lstField.get(0);
            if (nameSv.isEmpty())
                throw new Refused("row " + posLine + " names no node");
            if (!setName.add(nameSv))
                throw new Refused("row " + posLine + ": the name " + nameSv + " repeats");
            lstOut.add(claim(nameSv, "upstream.type", TYPE_NODE, null, TYPE_NODE, HIGH));
            lstOut.add(claim(nameSv, "title", nameSv, null, nameSv, HIGH));
            lstOut.add(claim(nameSv, "scan_url", empty(lstField.get(1)), null, lstField.get(1), HIGH));
            version(lstOut, nameSv, lstField.get(2));
        }
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    /**
     * @param lineOne one row of the body
     * @return its fields, trimmed. No quoting is recognised, because upstream
     *         uses none; a value carrying a comma would split and the row would
     *         then fail the field count rather than be read wrongly
     */
    private static List<String> fields(String lineOne) {
        List<String> lstOut = new ArrayList<>();
        for (String textOne : lineOne.split(",", -1)) {
            lstOut.add(textOne.trim());
        }
        return lstOut;
    }


    private void version(List<Claim> lstOut, String nameSv, String textValue) {
        Matcher matPatch = PAT_PATCH.matcher(textValue);
        Matcher matMinor = PAT_MINOR.matcher(textValue);
        if (matPatch.matches()) {
            lstOut.add(claim(nameSv, "version", textValue, "PATCH", textValue, HIGH));
        }
        else if (matMinor.matches()) {
            lstOut.add(claim(nameSv, "version", textValue, "MINOR", textValue, HIGH));
        }
        else {
            lstOut.add(claim(nameSv, "version", null, null, textValue, REVIEW));
        }
    }


    private static String empty(String textIn) {
        return textIn.isEmpty() ? null : textIn;
    }


    private Claim claim(String nameSv, String nameField, String textValue, String namePrecision,
            String textRaw, String nameConfidence) {
        return new Claim(nameSv, nameNetwork, KIND, null, nameField, textValue, namePrecision, textRaw,
                nameConfidence);
    }
}
