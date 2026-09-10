/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the SV Operations Schedule into claims.
 *
 * The body is a JSON array of records with ten fields each. A record whose type
 * is empty is history in an older format and is not read; every record dated
 * ahead of today has a type. Identity is the record's own id: it is unique in
 * every banked body and unchanged across them, and it does not depend on the
 * version or the date, which is what an identity must not do.
 *
 * The file is re-sorted upstream as dates pass, so two bodies holding the same
 * records in a different order are the same claims. Claims are sorted by
 * reference and field for that reason, and nothing here depends on position.
 *
 * What is read, and how sure the reading is:
 *
 * - kind from the type; Protocol Upgrades (LSU) splits into TOPOLOGY_FREEZE and
 *   PROTOCOL_UPGRADE on the title, and a type this does not know is
 *   REQUIRES_REVIEW rather than a guess;
 * - status Confirmed, To Be Confirmed and Cancelled as CONFIRMED, TENTATIVE and
 *   CANCELLED;
 * - the date as a DATE. Two records carry a clock time with no zone; the time
 *   stays in raw and never becomes an instant;
 * - the version from the title only. A weekly upgrade reads the whole title,
 *   including "remains on", which is published as no change rather than
 *   dropped; any other title yields its first x.y.z as PATCH or x.y.x as MINOR
 *   at MEDIUM confidence. The description is kept raw and never parsed.
 *
 * A body that is not an array of such records is refused whole.
 *
 * Author Claude/bentzn
 */
public final class ScheduleNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "SvOperationsScheduleNormalizer@1";

    /** The source this reads. */
    public static final String SOURCE_ID = "canton-foundation-sv-operations-schedule";

    private static final String HIGH = "HIGH";

    private static final String MEDIUM = "MEDIUM";

    private static final String REVIEW = "REQUIRES_REVIEW";

    private static final String KIND_UPGRADE = "NETWORK_UPGRADE";

    private static final String TYPE_LSU = "Protocol Upgrades (LSU)";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final List<String> LST_FIELD = List.of("id", "title", "start", "end", "status",
            "network", "type", "category", "description", "dependsOn");

    private static final Map<String, String> MAP_KIND = Map.of(
            "Weekly Upgrades", KIND_UPGRADE,
            "Minimum Splice Versions", "MINIMUM_SPLICE_VERSION",
            "Minimum Splice Dar versions", "MINIMUM_DAR_VERSION",
            "Splice Daml Model Effectivity", "DAML_MODEL_EFFECTIVITY",
            "Other Network Configuration Change", "NETWORK_CONFIGURATION_CHANGE");

    private static final Map<String, String> MAP_NETWORK =
            Map.of("DevNet", "DEVNET", "TestNet", "TESTNET", "MainNet", "MAINNET");

    private static final Map<String, String> MAP_STATUS =
            Map.of("Confirmed", "CONFIRMED", "To Be Confirmed", "TENTATIVE", "Cancelled", "CANCELLED");

    private static final Pattern PAT_DATE =
            Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2})(?: [0-9]{2}:[0-9]{2})?");

    private static final Pattern PAT_UPGRADE = Pattern.compile(
            "(DevNet|TestNet|MainNet) (upgrades to|remains on) Splice ([0-9]+\\.[0-9]+\\.[0-9]+)(?: \\([^()]*\\))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_VERSION =
            Pattern.compile("(?<![0-9.])([0-9]+)\\.([0-9]+)(?:\\.([0-9]+|x))?(?![0-9.])");


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
        Set<String> setId = new HashSet<>();
        List<Claim> lstOut = new ArrayList<>();
        int posRecord = 0;
        for (JsonNode nodeRec : nodeRoot) {
            check(nodeRec, posRecord);
            String idRec = nodeRec.get("id").asText();
            if (!setId.add(idRec))
                throw new Refused("record " + posRecord + ": id " + idRec + " repeats");
            if (!nodeRec.get("type").asText().isEmpty()) {
                lstOut.addAll(record(nodeRec));
            }
            posRecord++;
        }
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    private static void check(JsonNode nodeRec, int posRecord) throws Refused {
        if (!nodeRec.isObject())
            throw new Refused("record " + posRecord + " is not an object");
        for (String nameField : LST_FIELD) {
            JsonNode nodeField = nodeRec.get(nameField);
            if (nodeField == null)
                throw new Refused("record " + posRecord + " lacks " + nameField);
            if ("dependsOn".equals(nameField)) {
                if (!nodeField.isArray())
                    throw new Refused("record " + posRecord + ": dependsOn is not an array");
            }
            else if (!nodeField.isTextual()) {
                throw new Refused("record " + posRecord + ": " + nameField + " is not text");
            }
        }
    }


    private static List<Claim> record(JsonNode nodeRec) {
        String idRec = nodeRec.get("id").asText();
        String title = nodeRec.get("title").asText();
        String start = nodeRec.get("start").asText();
        String end = nodeRec.get("end").asText();
        String status = nodeRec.get("status").asText();
        String networkRaw = nodeRec.get("network").asText();
        String type = nodeRec.get("type").asText();
        String description = nodeRec.get("description").asText();

        String kind = kindOf(type, title);
        String network = MAP_NETWORK.get(networkRaw);
        String date = null;
        String period = null;
        Matcher matDate = PAT_DATE.matcher(start);
        if (matDate.matches()) {
            try {
                LocalDate dayStart = LocalDate.parse(matDate.group(1));
                date = matDate.group(1);
                period = String.format(Locale.ROOT, "%d-W%02d", dayStart.get(IsoFields.WEEK_BASED_YEAR),
                        dayStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            }
            catch (DateTimeParseException e) {
                date = null;
            }
        }

        Subject subj = new Subject(idRec, network, kind != null ? kind : REVIEW, period);
        List<Claim> lstOut = new ArrayList<>();
        lstOut.add(subj.claim("upstream.type", type, null, type, kind != null ? HIGH : REVIEW));
        lstOut.add(subj.claim("upstream.network", network, null, networkRaw, network != null ? HIGH : REVIEW));
        String statusRead = MAP_STATUS.get(status);
        lstOut.add(subj.claim("status", statusRead != null ? statusRead : REVIEW, null, status,
                statusRead != null ? HIGH : REVIEW));
        lstOut.add(subj.claim("effective.from", date, date != null ? "DATE" : "UNKNOWN", start,
                date != null ? HIGH : REVIEW));
        if (!end.equals(start)) {
            Matcher matEnd = PAT_DATE.matcher(end);
            boolean isDate = matEnd.matches();
            lstOut.add(subj.claim("effective.to", isDate ? matEnd.group(1) : null, isDate ? "DATE" : "UNKNOWN",
                    end, isDate ? HIGH : REVIEW));
        }
        lstOut.add(subj.claim("title", title, null, title, HIGH));
        if (!description.isEmpty()) {
            lstOut.add(subj.claim("description", description, null, description, HIGH));
        }
        versions(subj, kind, title, networkRaw, lstOut);
        return lstOut;
    }


    private static String kindOf(String type, String title) {
        if (TYPE_LSU.equals(type))
            return title.toLowerCase(Locale.ROOT).contains("topology freeze") ? "TOPOLOGY_FREEZE" : "PROTOCOL_UPGRADE";
        return MAP_KIND.get(type);
    }


    private static void versions(Subject subj, String kind, String title, String networkRaw, List<Claim> lstOut) {
        Matcher matUpgrade = KIND_UPGRADE.equals(kind) ? PAT_UPGRADE.matcher(title) : null;
        if (matUpgrade != null && matUpgrade.matches()) {
            boolean isAgreed = matUpgrade.group(1).toLowerCase(Locale.ROOT).equals(networkRaw.toLowerCase(Locale.ROOT));
            String confidence = isAgreed ? HIGH : REVIEW;
            boolean isRemain = "remains on".equals(matUpgrade.group(2).toLowerCase(Locale.ROOT));
            lstOut.add(subj.claim("version", matUpgrade.group(3), "PATCH", title, confidence));
            lstOut.add(subj.claim("version.change", isRemain ? "NONE" : "UPGRADE", null, title, confidence));
            return;
        }
        Matcher matVersion = PAT_VERSION.matcher(title);
        if (!matVersion.find())
            return;
        String third = matVersion.group(3);
        if (third == null || "x".equals(third)) {
            lstOut.add(subj.claim("version", matVersion.group(1) + "." + matVersion.group(2), "MINOR", title, MEDIUM));
        }
        else {
            lstOut.add(subj.claim("version", matVersion.group(1) + "." + matVersion.group(2) + "." + third, "PATCH",
                    title, MEDIUM));
        }
    }


    /** The subject every claim of one record shares. */
    private record Subject(String ref, String network, String kind, String period) {

        Claim claim(String field, String value, String precision, String raw, String confidence) {
            return new Claim(ref, network, kind, period, field, value, precision, raw, confidence);
        }
    }
}
