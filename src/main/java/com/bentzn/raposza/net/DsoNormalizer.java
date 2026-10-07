/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads one network's DSO document into claims, one record per Super Validator
 * node: the hosts it publishes.
 *
 * The document carries every node's state as the network itself holds it. What
 * is read from each is its scan url and one sequencer url per physical
 * synchronizer serial. The sequencer is the host a participant connects to and
 * the one that changes at every serial, so a node carries two while an upgrade
 * is in flight, and both are published. Nothing else in the document is read.
 *
 * Identity is the SV NAME, as in the roster, so the two describe one node and
 * are joined at publication. The roster is declared first and is the primary;
 * this source fills in what the roster does not carry.
 *
 * The sequencers are claimed as one value, each entry `serial=url`, highest
 * serial first, separated by one space. A url carries no space, and the order
 * is fixed, so a body that only re-orders its entries makes no revision.
 *
 * The shape is checked, not assumed. A body that is not an object, carries no
 * node, names a node twice, gives a node more or fewer than one synchronizer,
 * or holds a serial that is not a number, is refused whole. The document's own
 * isDevNet flag is checked against the network this instance reads, so a source
 * that begins answering for another network is refused rather than banked
 * under the wrong name.
 *
 * Author Claude/bentzn
 */
public final class DsoNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "DsoNormalizer@1";

    private static final String KIND = "SV_NODE";

    private static final String TYPE_NODE = "sv-node";

    private static final String HIGH = "HIGH";

    private static final String DEVNET = "DEVNET";

    private static final Pattern PAT_SERIAL = Pattern.compile("[0-9]{1,18}");

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String idSource;

    private final String nameNetwork;


    /**
     * @param idSourceUse the source this instance reads
     * @param nameNetworkUse the network as this project names it, MAINNET,
     *        TESTNET or DEVNET
     */
    public DsoNormalizer(String idSourceUse, String nameNetworkUse) {
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
        JsonNode nodeRoot;
        try {
            nodeRoot = MAPPER.readTree(bytesBody);
        }
        catch (IOException e) {
            throw new Refused("the body is not JSON: " + e.getMessage(), e);
        }
        if (nodeRoot == null || !nodeRoot.isObject())
            throw new Refused("the body is not an object");
        checkNetwork(nodeRoot);
        JsonNode nodeStates = nodeRoot.get("sv_node_states");
        if (nodeStates == null || !nodeStates.isArray() || nodeStates.isEmpty())
            throw new Refused("the body carries no sv_node_states");

        Set<String> setName = new HashSet<>();
        List<Claim> lstOut = new ArrayList<>();
        int posState = 0;
        for (JsonNode nodeState : nodeStates) {
            JsonNode nodePayload = nodeState.path("contract").path("payload");
            String nameSv = text(nodePayload.get("svName"));
            if (nameSv == null)
                throw new Refused("node state " + posState + " names no node");
            if (!setName.add(nameSv))
                throw new Refused("node state " + posState + ": the name " + nameSv + " repeats");
            JsonNode nodeSyncs = nodePayload.path("state").get("synchronizerNodes");
            if (nodeSyncs == null || !nodeSyncs.isArray() || nodeSyncs.size() != 1)
                throw new Refused(nameSv + " does not carry exactly one synchronizer");
            JsonNode nodePair = nodeSyncs.get(0);
            if (!nodePair.isArray() || nodePair.size() != 2 || !nodePair.get(1).isObject())
                throw new Refused(nameSv + ": the synchronizer entry is not a pair");
            JsonNode nodeSync = nodePair.get(1);
            String urlScan = text(nodeSync.path("scan").get("publicUrl"));
            String textSequencers = sequencers(nameSv, nodeSync.get("physicalSynchronizers"));
            lstOut.add(claim(nameSv, "upstream.type", TYPE_NODE));
            lstOut.add(claim(nameSv, "title", nameSv));
            lstOut.add(claim(nameSv, "scan_url", urlScan));
            lstOut.add(claim(nameSv, "sequencers", textSequencers));
            posState++;
        }
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    /**
     * @param nodeRoot the document
     * @throws Refused when the document's isDevNet flag is absent or disagrees
     *         with the network this instance reads
     */
    private void checkNetwork(JsonNode nodeRoot) throws Refused {
        JsonNode nodeFlag = nodeRoot.path("dso_rules").path("contract").path("payload").get("isDevNet");
        if (nodeFlag == null || !nodeFlag.isBoolean())
            throw new Refused("the body carries no isDevNet flag");
        if (nodeFlag.asBoolean() != DEVNET.equals(nameNetwork))
            throw new Refused("this source reads " + nameNetwork + " and the body says isDevNet "
                    + nodeFlag.asBoolean());
    }


    /**
     * @param nameSv the node, for the refusal message
     * @param nodePhysical the physicalSynchronizers array of one node
     * @return `serial=url` per serial that names a sequencer url, highest serial
     *         first, joined by one space; null when the node names none
     * @throws Refused when an entry is not a pair, its serial is not a number, a
     *         serial repeats, or a url is not text
     */
    private static String sequencers(String nameSv, JsonNode nodePhysical) throws Refused {
        if (nodePhysical == null || nodePhysical.isNull())
            return null;
        if (!nodePhysical.isArray())
            throw new Refused(nameSv + ": physicalSynchronizers is not an array");
        TreeMap<Long, String> mapUrl = new TreeMap<>(Comparator.reverseOrder());
        for (JsonNode nodeEntry : nodePhysical) {
            if (!nodeEntry.isArray() || nodeEntry.size() != 2)
                throw new Refused(nameSv + ": a physical synchronizer entry is not a pair");
            String textSerial = nodeEntry.get(0).asText();
            if (!PAT_SERIAL.matcher(textSerial).matches())
                throw new Refused(nameSv + ": the serial " + textSerial + " is not a number");
            JsonNode nodeUrl = nodeEntry.get(1).path("sequencer").get("url");
            if (nodeUrl == null || nodeUrl.isNull())
                continue;
            if (!nodeUrl.isTextual() || nodeUrl.asText().isEmpty() || nodeUrl.asText().contains(" "))
                throw new Refused(nameSv + ": the sequencer url of serial " + textSerial + " is not a url");
            if (mapUrl.put(Long.valueOf(textSerial), nodeUrl.asText()) != null)
                throw new Refused(nameSv + ": the serial " + textSerial + " repeats");
        }
        if (mapUrl.isEmpty())
            return null;
        StringBuilder sbOut = new StringBuilder();
        for (Long nSerial : mapUrl.keySet()) {
            if (sbOut.length() > 0) {
                sbOut.append(' ');
            }
            sbOut.append(nSerial).append('=').append(mapUrl.get(nSerial));
        }
        return sbOut.toString();
    }


    private static String text(JsonNode nodeField) {
        if (nodeField == null || !nodeField.isTextual() || nodeField.asText().isEmpty())
            return null;
        return nodeField.asText();
    }


    private Claim claim(String nameSv, String nameField, String textValue) {
        return new Claim(nameSv, nameNetwork, KIND, null, nameField, textValue, null, textValue, HIGH);
    }
}
