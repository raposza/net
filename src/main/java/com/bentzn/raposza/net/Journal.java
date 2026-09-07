/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The poll journal: one line per retrieval attempt, appended and never edited.
 *
 * It exists because the evidence store cannot hold two things that are primary
 * and cannot be re-derived. WHEN a body was retrieved is not in the body: the
 * retrieval time is deliberately kept out of any manifest, because putting it
 * there would make identical bytes hash differently on every poll and destroy
 * the deduplication that makes the store cheap. And an attempt that returned
 * nothing produces no bytes at all, so a failed or unchanged poll would leave no
 * trace whatever.
 *
 * A line carries the whole observation, not just a pointer to it, so a rebuild
 * never has to interpret evidence in order to index it. That matters most for
 * transports whose bodies have no internal structure the service understands.
 *
 * Author Claude/bentzn
 */
public final class Journal {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NAME_FILE = "poll.jsonl";


    private Journal() {
    }


    /** One retrieval attempt, exactly as it is written and read back. */
    public record Entry(
            String sourceId,
            String attemptedAt,
            String outcome,
            long durationMillis,
            String detail,
            String cursor,
            String collectorVersion,
            List<Banked> lstBanked) {
    }


    /**
     * @param dirRoot the journal root
     * @return the file every entry is appended to
     */
    public static Path file(Path dirRoot) {
        return dirRoot.resolve(NAME_FILE);
    }


    /**
     * Appends one entry and forces it to disk before returning. A poll that is
     * banked but not journalled would be invisible to a rebuild, so the cost of
     * the sync is the point of it.
     *
     * @param dirRoot the journal root
     * @param entry what happened
     * @throws IOException when the append fails
     */
    public static void append(Path dirRoot, Entry entry) throws IOException {
        Path fileOut = file(dirRoot);
        Files.createDirectories(dirRoot);
        List<Object> lstObs = new ArrayList<>();
        for (Banked banked : entry.lstBanked()) {
            Map<String, Object> mapObs = new LinkedHashMap<>();
            mapObs.put("key", banked.keyStorage());
            mapObs.put("sha", banked.shaContent());
            mapObs.put("mediaType", banked.mediaType());
            mapObs.put("revision", banked.revision());
            mapObs.put("upstreamAt", banked.upstreamAt());
            mapObs.put("httpStatus", banked.httpStatus());
            mapObs.put("etag", banked.etag());
            mapObs.put("lastModified", banked.lastModified());
            lstObs.add(mapObs);
        }
        Map<String, Object> mapOut = new LinkedHashMap<>();
        mapOut.put("sourceId", entry.sourceId());
        mapOut.put("attemptedAt", entry.attemptedAt());
        mapOut.put("outcome", entry.outcome());
        mapOut.put("durationMillis", Long.valueOf(entry.durationMillis()));
        mapOut.put("detail", entry.detail());
        mapOut.put("cursor", entry.cursor());
        mapOut.put("collector", entry.collectorVersion());
        mapOut.put("observations", lstObs);
        byte[] bytesLine = (MAPPER.writeValueAsString(mapOut) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream strmOut = new FileOutputStream(fileOut.toFile(), true)) {
            strmOut.write(bytesLine);
            strmOut.flush();
            strmOut.getFD().sync();
        }
    }


    /**
     * Reads the whole journal in the order it was written. A line that cannot be
     * parsed is skipped and counted rather than aborting the read: a truncated
     * last line after a hard stop must not cost the history before it.
     *
     * @param dirRoot the journal root
     * @return every entry that parsed, oldest first
     * @throws IOException when the file cannot be read
     */
    public static List<Entry> read(Path dirRoot) throws IOException {
        Path fileIn = file(dirRoot);
        List<Entry> lstEntry = new ArrayList<>();
        if (!Files.isRegularFile(fileIn)) {
            return lstEntry;
        }
        for (String lineOne : Files.readAllLines(fileIn, StandardCharsets.UTF_8)) {
            if (lineOne.isBlank()) {
                continue;
            }
            try {
                lstEntry.add(one(MAPPER.readValue(lineOne, new TypeReference<Map<String, Object>>() {
                })));
            }
            catch (IOException e) {
                System.err.println("journal: unreadable line skipped: " + e.getMessage());
            }
        }
        return lstEntry;
    }


    private static Entry one(Map<String, Object> mapIn) {
        List<Banked> lstBanked = new ArrayList<>();
        Object objObs = mapIn.get("observations");
        if (objObs instanceof List) {
            for (Object objOne : (List<?>) objObs) {
                if (objOne instanceof Map) {
                    lstBanked.add(banked((Map<?, ?>) objOne));
                }
            }
        }
        Object objMs = mapIn.get("durationMillis");
        return new Entry(
                str(mapIn.get("sourceId")),
                str(mapIn.get("attemptedAt")),
                str(mapIn.get("outcome")),
                objMs instanceof Number ? ((Number) objMs).longValue() : 0L,
                str(mapIn.get("detail")),
                str(mapIn.get("cursor")),
                str(mapIn.get("collector")),
                lstBanked);
    }


    private static Banked banked(Map<?, ?> mapObs) {
        Object objStatus = mapObs.get("httpStatus");
        return new Banked(
                str(mapObs.get("key")),
                str(mapObs.get("sha")),
                str(mapObs.get("mediaType")),
                str(mapObs.get("revision")),
                str(mapObs.get("upstreamAt")),
                objStatus instanceof Number ? Integer.valueOf(((Number) objStatus).intValue()) : null,
                str(mapObs.get("etag")),
                str(mapObs.get("lastModified")));
    }


    private static String str(Object objAny) {
        return objAny == null ? null : String.valueOf(objAny);
    }
}
