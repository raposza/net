/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One normalizer's reading of one field of one subject.
 *
 * The subject is what the claim is about: the upstream reference of the record,
 * the network, the kind of event and the ISO week it falls in. The raw text is
 * kept beside the value, so a reading that is wrong is a visible mismatch of two
 * recorded strings rather than a silent absence.
 *
 * Author Claude/bentzn
 *
 * @param subjectRef the upstream reference of the record the claim reads
 * @param subjectNetwork DEVNET, TESTNET, MAINNET, or null when none is named
 * @param subjectKind the event kind
 * @param subjectPeriod the ISO week of the effective date, or null
 * @param field what is claimed
 * @param value the value read, or null when it could not be read
 * @param precision the precision of the value where it has one, or null
 * @param raw the text the value was read from
 * @param confidence HIGH, MEDIUM, LOW or REQUIRES_REVIEW
 */
public record Claim(
        String subjectRef,
        String subjectNetwork,
        String subjectKind,
        String subjectPeriod,
        String field,
        String value,
        String precision,
        String raw,
        String confidence) {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    /**
     * @return the claim as one line of JSON with its keys in a fixed order, which
     *         is the form the fixture expectations are written in
     * @throws JsonProcessingException when the serializer fails
     */
    public String toLine() throws JsonProcessingException {
        Map<String, Object> mapOut = new LinkedHashMap<>();
        mapOut.put("ref", subjectRef);
        mapOut.put("network", subjectNetwork);
        mapOut.put("kind", subjectKind);
        mapOut.put("period", subjectPeriod);
        mapOut.put("field", field);
        mapOut.put("value", value);
        mapOut.put("precision", precision);
        mapOut.put("raw", raw);
        mapOut.put("confidence", confidence);
        return MAPPER.writeValueAsString(mapOut);
    }


    /**
     * @param lstClaim claims, in the order they are to be written
     * @return one line per claim, each terminated by a newline
     * @throws JsonProcessingException when the serializer fails
     */
    public static String lines(List<Claim> lstClaim) throws JsonProcessingException {
        StringBuilder sbOut = new StringBuilder();
        for (Claim claim : lstClaim) {
            sbOut.append(claim.toLine()).append('\n');
        }
        return sbOut.toString();
    }
}
