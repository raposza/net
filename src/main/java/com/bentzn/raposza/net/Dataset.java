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
 * Nothing in it is written by hand. The events are the ones the index derived
 * from banked bodies, the networks are derived from those events and from
 * nothing else, and the source list is the registry this build carries with the
 * state the index holds for each entry.
 *
 * What a publication is made of is stated in metadata.content, because an
 * environment that has banked nothing and an environment whose index is broken
 * both publish empty tables and a consumer must be able to tell them apart:
 *
 * <pre>
 * OBSERVED     the index was read and carried events
 * EMPTY        the index was read and carried none
 * UNAVAILABLE  the index could not be read
 * </pre>
 *
 * Author Claude/bentzn
 */
public final class Dataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter FMT_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter FMT_STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);


    private Dataset() {
    }


    /**
     * What one open connection to the index yielded, carried as one value so a
     * publication reads the index once and never half of it.
     *
     * @param lstSource the source list with the state the index holds
     * @param lstEvent the published events
     */
    public record Index(List<Object> lstSource, List<Object> lstEvent) {
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
     * @param idx what the index yielded, or null when it could not be read
     * @return the whole corpus: metadata, networks, events, sources
     */
    public static Map<String, Object> generate(String idPublication, Instant instNow, Index idx) {
        String stampNow = FMT_ISO.format(instNow);
        List<Object> lstEvent = idx == null || idx.lstEvent() == null ? List.of() : idx.lstEvent();
        Map<String, Object> mapDs = new LinkedHashMap<>();
        mapDs.put("metadata", map(
                "publicationId", idPublication,
                "createdAt", stampNow,
                "generator", "raposza-network-feed",
                "content", idx == null ? "UNAVAILABLE" : lstEvent.isEmpty() ? "EMPTY" : "OBSERVED"));
        mapDs.put("networks", Networks.derive(lstEvent, instNow, idPublication, stampNow));
        mapDs.put("events", lstEvent);
        mapDs.put("sources", idx == null || idx.lstSource() == null ? registry() : idx.lstSource());
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
            for (String nameNetwork : Networks.LST_NETWORK) {
                Map<String, Object> mapNetwork = readJson(dirAbs.resolve("networks")
                        .resolve(nameNetwork.toLowerCase(Locale.ROOT) + ".json"));
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
                        "pollSeconds", Integer.valueOf(def.pollSeconds()),
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
