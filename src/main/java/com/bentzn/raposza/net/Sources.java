/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The source registry. The definitions live in the artifact, as sources.json,
 * because a source definition carries authority and authority is policy: it
 * changes by a reviewed change to the repository and never by discovery.
 *
 * The source table is the projection of that file, plus the state the collector
 * keeps: the last revision it banked and when it last succeeded.
 *
 * Author Claude/bentzn
 */
public final class Sources {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RES_SOURCES = "/sources.json";


    private Sources() {
    }


    /**
     * @return every source definition carried by this build, in file order
     * @throws IOException when the resource is absent or malformed
     */
    public static List<SourceDef> load() throws IOException {
        try (InputStream strmIn = Sources.class.getResourceAsStream(RES_SOURCES)) {
            if (strmIn == null)
                throw new IOException("no " + RES_SOURCES + " in the artifact");
            Map<String, Object> mapFile = MAPPER.readValue(strmIn, new TypeReference<Map<String, Object>>() {
            });
            List<SourceDef> lstDef = new ArrayList<>();
            Object objList = mapFile.get("sources");
            if (objList instanceof List) {
                for (Object objOne : (List<?>) objList) {
                    if (objOne instanceof Map) {
                        lstDef.add(one((Map<?, ?>) objOne));
                    }
                }
            }
            return lstDef;
        }
    }


    /**
     * Brings the source table into line with the definitions. Columns the
     * collector owns are never touched here.
     *
     * @param conn an open connection
     * @param lstDef the definitions
     * @throws SQLException when the write fails
     */
    public static void sync(Connection conn, List<SourceDef> lstDef) throws SQLException {
        String sqlUpsert = "merge into source (id, publisher, transport, url, branch, path_prefix,"
                + " source_authority, claim_policy, poll_seconds, include_pre_releases, enabled,"
                + " updated_at) key (id) values (?,?,?,?,?,?,?,?,?,?,?,current_timestamp)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlUpsert)) {
            for (SourceDef def : lstDef) {
                stmt.setString(1, def.id());
                stmt.setString(2, def.publisher());
                stmt.setString(3, def.transport());
                stmt.setString(4, def.url());
                stmt.setString(5, def.branch());
                stmt.setString(6, def.pathPrefix());
                stmt.setString(7, def.sourceAuthority());
                stmt.setString(8, def.claimPolicy());
                stmt.setInt(9, def.pollSeconds());
                stmt.setBoolean(10, def.includePreReleases());
                stmt.setBoolean(11, def.enabled());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }


    /**
     * @param conn an open connection
     * @param idSource the source
     * @return the newest revision already banked, or null when none is
     * @throws SQLException when the read fails
     */
    public static String lastRevision(Connection conn, String idSource) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("select last_revision from source where id = ?")) {
            stmt.setString(1, idSource);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }


    /**
     * What the last observation of this source carried, so the next request can
     * be conditional.
     *
     * @param conn an open connection
     * @param idSource the source
     * @return the validators and content hash, or null when nothing is banked
     * @throws SQLException when the read fails
     */
    public static HttpCollector.State lastHttp(Connection conn, String idSource) throws SQLException {
        String sqlLast = "select etag, last_modified, content_sha256 from source_observation"
                + " where source_id = ? order by retrieved_at desc limit 1";
        try (PreparedStatement stmt = conn.prepareStatement(sqlLast)) {
            stmt.setString(1, idSource);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next())
                    return null;
                return new HttpCollector.State(rs.getString(1), rs.getString(2), rs.getString(3));
            }
        }
    }


    /**
     * When the source was last POLLED, which is what the schedule runs on. A
     * source that is failing must still be retried on its own interval rather
     * than on every turn of the worker, so this is deliberately not the last
     * success.
     *
     * @param conn an open connection
     * @param idSource the source
     * @return the moment of the last attempt, or null when there has been none
     * @throws SQLException when the read fails
     */
    public static Instant lastAttempt(Connection conn, String idSource) throws SQLException {
        String sqlLast = "select max(attempted_at) from poll_attempt where source_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sqlLast)) {
            stmt.setString(1, idSource);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next())
                    return null;
                OffsetDateTime stampLast = rs.getObject(1, OffsetDateTime.class);
                return stampLast == null ? null : stampLast.toInstant();
            }
        }
    }


    /**
     * @param conn an open connection
     * @param idSource the source
     * @return the moment the source last produced a usable poll, or null
     * @throws SQLException when the read fails
     */
    public static Instant lastSuccess(Connection conn, String idSource) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("select last_success_at from source where id = ?")) {
            stmt.setString(1, idSource);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next())
                    return null;
                OffsetDateTime stampLast = rs.getObject(1, OffsetDateTime.class);
                return stampLast == null ? null : stampLast.toInstant();
            }
        }
    }


    /**
     * Records the outcome of a poll against the source itself. A revision of null
     * leaves the cursor where it was, which is what an unchanged or failed poll
     * must do.
     *
     * @param conn an open connection
     * @param idSource the source
     * @param revision the newest revision banked, or null
     * @param nameState HEALTHY or DEGRADED
     * @param instNow the moment of the poll
     * @throws SQLException when the write fails
     */
    public static void mark(Connection conn, String idSource, String revision, String nameState, Instant instNow)
            throws SQLException {
        boolean isHealthy = "HEALTHY".equals(nameState);
        StringBuilder sbSql = new StringBuilder("update source set state = ?, updated_at = current_timestamp");
        if (revision != null) {
            sbSql.append(", last_revision = ?");
        }
        if (isHealthy) {
            sbSql.append(", last_success_at = ?");
        }
        sbSql.append(" where id = ?");
        try (PreparedStatement stmt = conn.prepareStatement(sbSql.toString())) {
            int cntArg = 1;
            stmt.setString(cntArg++, nameState);
            if (revision != null) {
                stmt.setString(cntArg++, revision);
            }
            if (isHealthy) {
                stmt.setObject(cntArg++, OffsetDateTime.ofInstant(instNow, ZoneOffset.UTC));
            }
            stmt.setString(cntArg, idSource);
            stmt.executeUpdate();
        }
    }


    private static SourceDef one(Map<?, ?> mapDef) {
        return new SourceDef(
                text(mapDef, "id", null),
                text(mapDef, "publisher", ""),
                text(mapDef, "transport", ""),
                text(mapDef, "url", ""),
                text(mapDef, "branch", null),
                text(mapDef, "pathPrefix", null),
                text(mapDef, "sourceAuthority", "COMMUNITY"),
                text(mapDef, "claimPolicy", ""),
                number(mapDef, "pollSeconds", 300),
                flag(mapDef, "includePreReleases", false),
                flag(mapDef, "enabled", false));
    }


    private static String text(Map<?, ?> mapDef, String nameKey, String valDefault) {
        Object objVal = mapDef.get(nameKey);
        return objVal == null ? valDefault : String.valueOf(objVal);
    }


    private static int number(Map<?, ?> mapDef, String nameKey, int valDefault) {
        Object objVal = mapDef.get(nameKey);
        return objVal instanceof Number ? ((Number) objVal).intValue() : valDefault;
    }


    private static boolean flag(Map<?, ?> mapDef, String nameKey, boolean valDefault) {
        Object objVal = mapDef.get(nameKey);
        return objVal instanceof Boolean ? ((Boolean) objVal).booleanValue() : valDefault;
    }
}
