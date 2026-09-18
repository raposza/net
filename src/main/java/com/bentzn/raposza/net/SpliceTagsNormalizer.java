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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the Splice tags endpoint into claims.
 *
 * The body is a JSON array of tags, each with a name, a commit sha and three
 * urls derived from them. Identity is the TAG NAME: it is upstream's own
 * reference, unique in the body, and it is what a reader of the repository
 * would cite. For most tags the name is also the version, which is the one
 * place this source strains the rule that identity must not depend on version -
 * it is not derived from the version, it is the same string upstream chose.
 *
 * A TAG CARRIES NO DATE. The endpoint states that a tag exists and what commit
 * it points at, and nothing else, so no effective date is claimed and none is
 * manufactured from the poll. When the release became visible is the first
 * observation that carried it, which the event holds already; the tagged
 * commit's own date is 46 minutes earlier than the tag is visible here, so
 * reading one as the other would be wrong in both directions.
 *
 * What is read, and how sure the reading is:
 *
 * - the version from the name, which must be the WHOLE name: x.y.z or vx.y.z is
 *   a PATCH, x.y or vx.y a MINOR, and anything else yields no version at
 *   REQUIRES_REVIEW rather than a guess. A moving or named tag such as one
 *   pointing at a development head is real and is published, with no version;
 * - the commit sha, as commit_sha, because it is the only field of a tag that
 *   can move under a fixed name and a move is worth a revision. The description
 *   is left to the release-notes source, which carries the notes themselves;
 * - no status. A tag exists or it does not; upstream states nothing that
 *   confirms, plans or cancels it, and a status invented here would be read as
 *   one that was published.
 *
 * A body that is not an array of such tags is refused whole.
 *
 * Author Claude/bentzn
 */
public final class SpliceTagsNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "SpliceTagsNormalizer@1";

    /** The source this reads. */
    public static final String SOURCE_ID = "splice-tags";

    private static final String KIND = "SOFTWARE_RELEASE";

    private static final String TYPE_TAG = "tag";

    private static final String HIGH = "HIGH";

    private static final String REVIEW = "REQUIRES_REVIEW";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern PAT_PATCH = Pattern.compile("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)");

    private static final Pattern PAT_MINOR = Pattern.compile("v?([0-9]+)\\.([0-9]+)");


    @Override
    public String id() {
        return ID;
    }


    @Override
    public String sourceId() {
        return SOURCE_ID;
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
        if (nodeRoot == null || !nodeRoot.isArray() || nodeRoot.size() == 0)
            throw new Refused("the body is not a non-empty array");
        Set<String> setName = new HashSet<>();
        List<Claim> lstOut = new ArrayList<>();
        int posTag = 0;
        for (JsonNode nodeTag : nodeRoot) {
            lstOut.addAll(tag(nodeTag, posTag, setName));
            posTag++;
        }
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    private static List<Claim> tag(JsonNode nodeTag, int posTag, Set<String> setName) throws Refused {
        if (!nodeTag.isObject())
            throw new Refused("tag " + posTag + " is not an object");
        String nameTag = text(nodeTag.get("name"));
        if (nameTag == null)
            throw new Refused("tag " + posTag + " has no name");
        JsonNode nodeCommit = nodeTag.get("commit");
        String shaCommit = nodeCommit == null ? null : text(nodeCommit.get("sha"));
        if (shaCommit == null)
            throw new Refused("tag " + posTag + " (" + nameTag + ") has no commit sha");
        if (!setName.add(nameTag))
            throw new Refused("tag " + posTag + ": name " + nameTag + " repeats");

        List<Claim> lstOut = new ArrayList<>();
        lstOut.add(claim(nameTag, "upstream.type", TYPE_TAG, null, TYPE_TAG, HIGH));
        lstOut.add(claim(nameTag, "title", nameTag, null, nameTag, HIGH));
        lstOut.add(claim(nameTag, "commit_sha", shaCommit, null, shaCommit, HIGH));
        Matcher matPatch = PAT_PATCH.matcher(nameTag);
        Matcher matMinor = PAT_MINOR.matcher(nameTag);
        if (matPatch.matches()) {
            lstOut.add(claim(nameTag, "version",
                    matPatch.group(1) + "." + matPatch.group(2) + "." + matPatch.group(3), "PATCH",
                    nameTag, HIGH));
        }
        else if (matMinor.matches()) {
            lstOut.add(claim(nameTag, "version", matMinor.group(1) + "." + matMinor.group(2), "MINOR",
                    nameTag, HIGH));
        }
        else {
            lstOut.add(claim(nameTag, "version", null, null, nameTag, REVIEW));
        }
        return lstOut;
    }


    private static String text(JsonNode nodeField) {
        if (nodeField == null || !nodeField.isTextual() || nodeField.asText().isEmpty())
            return null;
        return nodeField.asText();
    }


    private static Claim claim(String nameTag, String field, String value, String precision, String raw,
            String confidence) {
        return new Claim(nameTag, null, KIND, null, field, value, precision, raw, confidence);
    }
}
