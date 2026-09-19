/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads one network's deployment information into claims.
 *
 * This is the first source that reports what a network IS RUNNING rather than
 * what its operators intend. The schedule states a plan; this states the
 * synchronizer's own answer, so where the two disagree the disagreement is real
 * and both are published.
 *
 * One endpoint per network, so one instance of this class per network, each
 * pinned to its own source id. The body names the network it describes and that
 * name is CHECKED against the one this instance was configured for: a source
 * pinned to one network that begins answering for another is a fault, not data,
 * and it is refused rather than banked under the wrong name.
 *
 * What is read:
 *
 * <pre>
 * version            synchronizer.current.version - what the network runs
 * sv_version         sv.version - the SV application beside it. Equal to the
 *                    synchronizer version on all three networks at the time this
 *                    was written, and a different thing, so both are published
 * serial_id          synchronizer.current.serial_id. Upstream increments it by
 *                    one for each logical synchronizer upgrade, and it now
 *                    carries what the migration id used to: release names, DNS
 *                    entries, database names, chain ids and port numbers
 * migration_id       sv.migration_id. FROZEN upstream and configured once, so a
 *                    value that moves here is not an upgrade, it is something
 *                    nobody expects to happen
 * chain_id_suffix    synchronizer.current.chain_id_suffix - identity
 * successor_version  synchronizer.successor.version, null while no upgrade is
 *                    in flight
 * legacy_version     synchronizer.legacy.version, null while no upgrade is in
 *                    flight
 * </pre>
 *
 * The serial id was left out of the first cut of this class on the reasoning
 * that it moved without a version moving and a consumer could not act on it.
 * That was read off the field name. Upstream states the opposite, and the banked
 * bodies agree with upstream: one network carries migration id 4 beside serial
 * id 5, and the other two carry migration id 1 beside serial ids 2 and 5.
 *
 * `sv.serial_id` is not read separately. It equals the synchronizer's on every
 * banked body, and upstream describes the serial id as a property of the
 * synchronizer deployment, so the synchronizer's is the one carried.
 *
 * Author Claude/bentzn
 */
public final class NetworkInfoNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "NetworkInfoNormalizer@1";

    private static final String KIND = "NETWORK_DEPLOYMENT";

    private static final String TYPE_INFO = "deployment-info";

    private static final String HIGH = "HIGH";

    private static final String REVIEW = "REQUIRES_REVIEW";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern PAT_PATCH = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)");

    private static final Pattern PAT_MINOR = Pattern.compile("([0-9]+)\\.([0-9]+)");

    private final String idSource;

    private final String nameNetwork;

    private final String nameUpstream;


    /**
     * @param idSourceUse the source this instance reads
     * @param nameNetworkUse the network as this project names it, MAINNET,
     *        TESTNET or DEVNET
     * @param nameUpstreamUse the network as the body names itself, which is
     *        checked rather than assumed
     */
    public NetworkInfoNormalizer(String idSourceUse, String nameNetworkUse, String nameUpstreamUse) {
        this.idSource = idSourceUse;
        this.nameNetwork = nameNetworkUse;
        this.nameUpstream = nameUpstreamUse;
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
        JsonNode nodeRoot;
        try {
            nodeRoot = MAPPER.readTree(bytesBody);
        }
        catch (IOException e) {
            throw new Refused("the body is not JSON: " + e.getMessage(), e);
        }
        if (nodeRoot == null || !nodeRoot.isObject())
            throw new Refused("the body is not an object");
        String nameGot = text(nodeRoot.get("network"));
        if (nameGot == null)
            throw new Refused("the body names no network");
        if (!nameUpstream.equals(nameGot))
            throw new Refused("this source is pinned to " + nameUpstream + " and the body says " + nameGot);
        JsonNode nodeSv = nodeRoot.get("sv");
        JsonNode nodeSync = nodeRoot.get("synchronizer");
        if (nodeSv == null || !nodeSv.isObject())
            throw new Refused("the body carries no sv object");
        if (nodeSync == null || !nodeSync.isObject())
            throw new Refused("the body carries no synchronizer object");
        JsonNode nodeCurrent = nodeSync.get("current");
        if (nodeCurrent == null || !nodeCurrent.isObject())
            throw new Refused("the synchronizer states no current deployment");

        List<Claim> lstOut = new ArrayList<>();
        lstOut.add(claim("upstream.type", TYPE_INFO, null, TYPE_INFO, HIGH));
        lstOut.add(claim("title", nameNetwork, null, nameGot, HIGH));
        version(lstOut, "version", text(nodeCurrent.get("version")));
        lstOut.add(one("sv_version", text(nodeSv.get("version"))));
        lstOut.add(one("migration_id", number(nodeSv.get("migration_id"))));
        lstOut.add(one("serial_id", number(nodeCurrent.get("serial_id"))));
        lstOut.add(one("chain_id_suffix", text(nodeCurrent.get("chain_id_suffix"))));
        lstOut.add(one("successor_version", nested(nodeSync.get("successor"))));
        lstOut.add(one("legacy_version", nested(nodeSync.get("legacy"))));
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    /**
     * The running version, with its precision taken from its own shape. A value
     * that is not a dotted number is carried raw at REQUIRES_REVIEW rather than
     * being forced into a version, because this field is what a consumer
     * compares its own deployment against.
     */
    private void version(List<Claim> lstOut, String nameField, String textValue) {
        if (textValue == null) {
            lstOut.add(claim(nameField, null, null, null, REVIEW));
            return;
        }
        Matcher matPatch = PAT_PATCH.matcher(textValue);
        Matcher matMinor = PAT_MINOR.matcher(textValue);
        if (matPatch.matches()) {
            lstOut.add(claim(nameField, textValue, "PATCH", textValue, HIGH));
        }
        else if (matMinor.matches()) {
            lstOut.add(claim(nameField, textValue, "MINOR", textValue, HIGH));
        }
        else {
            lstOut.add(claim(nameField, null, null, textValue, REVIEW));
        }
    }


    /**
     * @param nodeSide the successor or legacy node, which is null between
     *        migrations
     * @return its version, or null where the node is absent or null
     */
    private static String nested(JsonNode nodeSide) {
        if (nodeSide == null || !nodeSide.isObject())
            return null;
        return text(nodeSide.get("version"));
    }


    private static String number(JsonNode nodeField) {
        if (nodeField == null || !nodeField.isNumber())
            return null;
        return nodeField.asText();
    }


    private static String text(JsonNode nodeField) {
        if (nodeField == null || !nodeField.isTextual() || nodeField.asText().isEmpty())
            return null;
        return nodeField.asText();
    }


    private Claim one(String nameField, String textValue) {
        return claim(nameField, textValue, null, textValue, HIGH);
    }


    private Claim claim(String nameField, String textValue, String namePrecision, String textRaw,
            String nameConfidence) {
        return new Claim(nameUpstream, nameNetwork, KIND, null, nameField, textValue, namePrecision, textRaw,
                nameConfidence);
    }
}
