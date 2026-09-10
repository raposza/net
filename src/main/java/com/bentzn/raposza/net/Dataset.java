/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The published dataset: generation of a corpus, atomic write of the directory a
 * static server exposes, and read-back of that directory.
 *
 * The networks and the events are placeholder content: they carry the shape of
 * the contract and not observed facts, and every value in them was written by
 * hand. The source list is not placeholder. It is the registry the collector
 * actually runs on, with the state the index holds for each entry, so a reader
 * is never told about a source this build does not have.
 *
 * Author Claude/bentzn
 */
public final class Dataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter FMT_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter FMT_STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final List<String> LST_NETWORK = List.of("devnet", "testnet", "mainnet");


    private Dataset() {
    }


    /**
     * @param instNow the moment of publication
     * @return a publication identifier, monotonic in time and readable
     */
    public static String newPublicationId(Instant instNow) {
        return "pub_" + FMT_STAMP.format(instNow);
    }


    /**
     * @param idPublication identifier stamped into every record
     * @param instNow the moment of publication
     * @param lstSource the source list as the index holds it, or null to fall back
     *        to the registry this build carries
     * @return the whole corpus: metadata, networks, events, sources
     */
    public static Map<String, Object> generate(String idPublication, Instant instNow, List<Object> lstSource) {
        String stampNow = FMT_ISO.format(instNow);
        Map<String, Object> mapDs = new LinkedHashMap<>();
        mapDs.put("metadata", map(
                "publicationId", idPublication,
                "createdAt", stampNow,
                "generator", "raposza-network-feed",
                "content", "PLACEHOLDER"));
        mapDs.put("networks", networks(idPublication, stampNow));
        mapDs.put("events", events(stampNow));
        mapDs.put("sources", lstSource == null ? registry() : lstSource);
        return mapDs;
    }


    /**
     * @param instMoment a moment, or null
     * @return it in the second-precision UTC form every timestamp here uses
     */
    public static String iso(Instant instMoment) {
        return instMoment == null ? null : FMT_ISO.format(instMoment);
    }


    /**
     * Writes the corpus into a sibling temporary directory and moves it into place,
     * so a reader never sees a half-written publication.
     *
     * @param dirOut the dataset directory
     * @param mapDs the corpus
     * @throws IOException if any part of the write or the move fails
     */
    public static void write(Path dirOut, Map<String, Object> mapDs) throws IOException {
        Path dirAbs = dirOut.toAbsolutePath().normalize();
        Path dirTmp = dirAbs.resolveSibling(dirAbs.getFileName() + ".tmp");
        Path dirOld = dirAbs.resolveSibling(dirAbs.getFileName() + ".old");
        String idPublication = String.valueOf(meta(mapDs).get("publicationId"));

        Files.createDirectories(dirAbs.getParent());
        deleteTree(dirTmp);
        deleteTree(dirOld);
        Files.createDirectories(dirTmp.resolve("networks"));
        Files.createDirectories(dirTmp.resolve("events"));

        writeJson(dirTmp.resolve("metadata.json"), mapDs.get("metadata"));
        for (Map<String, Object> mapNetwork : castList(mapDs.get("networks"))) {
            String idNetwork = String.valueOf(mapNetwork.get("network")).toLowerCase(Locale.ROOT);
            writeJson(dirTmp.resolve("networks").resolve(idNetwork + ".json"), mapNetwork);
        }
        writeJson(dirTmp.resolve("events").resolve("current.json"),
                map("publicationId", idPublication, "events", mapDs.get("events")));
        writeJson(dirTmp.resolve("sources.json"),
                map("publicationId", idPublication, "sources", mapDs.get("sources")));

        if (Files.exists(dirAbs)) {
            Files.move(dirAbs, dirOld, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(dirTmp, dirAbs, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(dirOld);
    }


    /**
     * @param dirIn the dataset directory
     * @return the corpus, or null when the directory is absent or incomplete
     */
    public static Map<String, Object> read(Path dirIn) {
        Path dirAbs = dirIn.toAbsolutePath().normalize();
        try {
            Map<String, Object> mapMeta = readJson(dirAbs.resolve("metadata.json"));
            Map<String, Object> mapEvents = readJson(dirAbs.resolve("events").resolve("current.json"));
            Map<String, Object> mapSources = readJson(dirAbs.resolve("sources.json"));
            if (mapMeta == null || mapEvents == null || mapSources == null) {
                return null;
            }
            List<Object> lstNetwork = new ArrayList<>();
            for (String idNetwork : LST_NETWORK) {
                Map<String, Object> mapNetwork = readJson(dirAbs.resolve("networks").resolve(idNetwork + ".json"));
                if (mapNetwork == null) {
                    return null;
                }
                lstNetwork.add(mapNetwork);
            }
            Map<String, Object> mapDs = new LinkedHashMap<>();
            mapDs.put("metadata", mapMeta);
            mapDs.put("networks", lstNetwork);
            mapDs.put("events", mapEvents.get("events"));
            mapDs.put("sources", mapSources.get("sources"));
            return mapDs;
        }
        catch (IOException e) {
            return null;
        }
    }


    /**
     * @param mapDs the corpus
     * @return its metadata block, never null
     */
    public static Map<String, Object> meta(Map<String, Object> mapDs) {
        Object objMeta = mapDs.get("metadata");
        if (objMeta instanceof Map) {
            return castMap(objMeta);
        }
        return new LinkedHashMap<>();
    }


    /**
     * @param kvPair alternating key and value
     * @return an insertion-ordered map, so the serialized field order is stable
     */
    public static Map<String, Object> map(Object... kvPair) {
        Map<String, Object> mapOut = new LinkedHashMap<>();
        for (int cntKv = 0; cntKv + 1 < kvPair.length; cntKv += 2) {
            mapOut.put(String.valueOf(kvPair[cntKv]), kvPair[cntKv + 1]);
        }
        return mapOut;
    }


    private static List<Object> networks(String idPublication, String stampNow) {
        List<Object> lstOut = new ArrayList<>();
        lstOut.add(map(
                "network", "DEVNET",
                "splice", map("currentVersion", "0.7.5", "minimumVersion", "0.7.4"),
                "synchronizer", map("version", "0.7.5", "serialId", 6),
                "nextEvent", "evt_devnet_2026w38",
                "publicationId", idPublication,
                "updatedAt", stampNow));
        lstOut.add(map(
                "network", "TESTNET",
                "splice", map("currentVersion", "0.7.4", "minimumVersion", "0.7.3"),
                "synchronizer", map("version", "0.7.4", "serialId", 5),
                "nextEvent", "evt_testnet_2026w38",
                "publicationId", idPublication,
                "updatedAt", stampNow));
        lstOut.add(map(
                "network", "MAINNET",
                "splice", map("currentVersion", "0.7.3", "minimumVersion", "0.7.2"),
                "synchronizer", map("version", "0.7.3", "serialId", 5),
                "nextEvent", "evt_mainnet_2026w39",
                "publicationId", idPublication,
                "updatedAt", stampNow));
        return lstOut;
    }


    private static List<Object> events(String stampNow) {
        List<Object> lstOut = new ArrayList<>();
        lstOut.add(event("evt_devnet_2026w38", "NETWORK_UPGRADE_PLANNED", "DEVNET",
                "WEEKLY_UPGRADE", "2026-W38", "0.7.6", "CONFIRMED",
                "2026-09-15T13:00:00Z", "TIMESTAMP", stampNow));
        lstOut.add(event("evt_testnet_2026w38", "NETWORK_UPGRADE_PLANNED", "TESTNET",
                "WEEKLY_UPGRADE", "2026-W38", "0.7.5", "PLANNED",
                "2026-09-17", "DATE", stampNow));
        lstOut.add(event("evt_mainnet_2026w39", "NETWORK_UPGRADE_PLANNED", "MAINNET",
                "WEEKLY_UPGRADE", "2026-W39", null, "TENTATIVE",
                "2026-09-22", "DATE", stampNow));
        lstOut.add(event("evt_mainnet_minver_2026w39", "MINIMUM_SPLICE_VERSION_CHANGED", "MAINNET",
                "MINIMUM_VERSION", "2026-W39", "0.7.3", "PLANNED",
                "2026-09-22", "DATE", stampNow));
        return lstOut;
    }


    private static Map<String, Object> event(String idEvent, String typeEvent, String nameNetwork,
            String kindSlot, String periodSlot, String verSubject, String nameStatus,
            String stampFrom, String namePrecision, String stampNow) {
        return map(
                "id", idEvent,
                "schemaVersion", 1,
                "type", typeEvent,
                "network", nameNetwork,
                "slot", map("kind", kindSlot, "period", periodSlot),
                "subject", map("type", "SPLICE", "version", verSubject),
                "status", nameStatus,
                "effective", map("from", stampFrom, "precision", namePrecision),
                "authority", "OFFICIAL",
                "confidence", "HIGH",
                "firstObservedAt", stampNow,
                "lastObservedAt", stampNow,
                "revision", 1,
                "provenance", List.of(map(
                        "sourceId", "canton-foundation-sv-operations-schedule",
                        "observationId", "obs_placeholder",
                        "normalizer", "none")));
    }


    /**
     * The source list as the registry alone describes it, used when no index is
     * open: the api falls back to this before the first publication exists, and
     * it must never invent a source or a state it has not read.
     */
    private static List<Object> registry() {
        List<Object> lstOut = new ArrayList<>();
        try {
            for (SourceDef def : Sources.load()) {
                lstOut.add(map(
                        "id", def.id(),
                        "publisher", def.publisher(),
                        "authority", def.sourceAuthority(),
                        "enabled", Boolean.valueOf(def.enabled()),
                        "state", "UNKNOWN",
                        "lastSuccessAt", null));
            }
        }
        catch (IOException e) {
            System.err.println("source registry unavailable: " + e);
        }
        return lstOut;
    }


    private static void writeJson(Path fileOut, Object objValue) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(fileOut.toFile(), objValue);
    }


    private static Map<String, Object> readJson(Path fileIn) throws IOException {
        if (!Files.isRegularFile(fileIn)) {
            return null;
        }
        return MAPPER.readValue(fileIn.toFile(), new TypeReference<Map<String, Object>>() {
        });
    }


    private static void deleteTree(Path dirGone) throws IOException {
        if (!Files.exists(dirGone)) {
            return;
        }
        try (Stream<Path> strmPath = Files.walk(dirGone)) {
            List<Path> lstPath = strmPath.sorted(Comparator.reverseOrder()).toList();
            for (Path fileGone : lstPath) {
                Files.deleteIfExists(fileGone);
            }
        }
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object objAny) {
        return (Map<String, Object>) objAny;
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object objAny) {
        return (List<Map<String, Object>>) objAny;
    }
}
