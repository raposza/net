/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The database: an embedded H2 file, opened by one process.
 *
 * It is a derived index, not a system of record. Everything in it can be
 * reconstructed from the evidence store and the poll journal, which is why there
 * are no migrations: a schema that has moved on is not migrated, the index is
 * dropped and rebuilt. The index records the digest of the schema it was built
 * with, so a build carrying another schema recognises it and drops it rather
 * than writing into tables of the wrong shape.
 *
 * Embedded H2 admits ONE writer process. The worker owns it. The api reads the
 * published dataset directory and opens no database at all, which is what makes
 * one file safe across the two services.
 *
 * Author Claude/bentzn
 */
public final class Db {

    private static final String RES_SCHEMA = "/schema.sql";

    private static final String KEY_SCHEMA = "schema_sha256";


    private Db() {
    }


    /**
     * @return an open connection to the configured database; the caller closes it
     * @throws SQLException when the file cannot be opened
     */
    public static Connection connection() throws SQLException {
        return DriverManager.getConnection(Config.dbUrl(), Config.dbUser(), Config.dbPassword());
    }


    /**
     * Brings the schema into existence and records its digest. Idempotent: every
     * statement is guarded, so a second run changes nothing. An index built with
     * another schema is dropped first, so tables of the wrong shape are never
     * marked current; the caller that sees true rebuilds.
     *
     * @param conn an open connection
     * @return true when a stale index was dropped, leaving an empty one
     * @throws SQLException when a statement fails
     * @throws IOException when the schema resource is absent
     */
    public static boolean schema(Connection conn) throws SQLException, IOException {
        String sqlAll = read();
        boolean isDropped = false;
        if (isStale(conn)) {
            drop(conn);
            isDropped = true;
        }
        try (Statement stmt = conn.createStatement()) {
            for (String sqlOne : statements(sqlAll)) {
                stmt.execute(sqlOne);
            }
        }
        try (PreparedStatement stmt = conn.prepareStatement(
                "merge into index_meta (meta_key, meta_value) key (meta_key) values (?, ?)")) {
            stmt.setString(1, KEY_SCHEMA);
            stmt.setString(2, digest(sqlAll));
            stmt.executeUpdate();
        }
        return isDropped;
    }


    /**
     * @param conn an open connection
     * @return true when the file holds an index built with a schema other than
     *         the one this build carries, including one from before the digest
     *         was recorded; false for an empty file
     * @throws SQLException when the file cannot be read
     * @throws IOException when the schema resource is absent
     */
    public static boolean isStale(Connection conn) throws SQLException, IOException {
        if (!hasTable(conn, "POLL_ATTEMPT"))
            return false;
        if (!hasTable(conn, "INDEX_META"))
            return true;
        try (PreparedStatement stmt = conn.prepareStatement("select meta_value from index_meta where meta_key = ?")) {
            stmt.setString(1, KEY_SCHEMA);
            try (ResultSet rs = stmt.executeQuery()) {
                return !rs.next() || !digest(read()).equals(rs.getString(1));
            }
        }
    }


    /**
     * Removes everything, so the next schema call starts from nothing. This is
     * the forced form of the rebuild the worker does on its own when it finds
     * the index empty.
     *
     * @param conn an open connection
     * @throws SQLException when the drop fails
     */
    public static void drop(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("drop all objects");
        }
    }


    /**
     * @param conn an open connection
     * @return true when nothing has been banked yet, which is the trigger to
     *         rebuild from evidence and journal
     * @throws SQLException when the count fails
     */
    public static boolean isEmpty(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select count(*) from poll_attempt")) {
            return rs.next() && rs.getLong(1) == 0L;
        }
    }


    /**
     * No table type is passed: H2 does not report its tables under the type name
     * other databases use, and a filter on it would find nothing and call every
     * index current.
     */
    private static boolean hasTable(Connection conn, String nameTable) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, nameTable, null)) {
            while (rs.next()) {
                if (nameTable.equals(rs.getString("TABLE_NAME")))
                    return true;
            }
            return false;
        }
    }


    private static String read() throws IOException {
        try (InputStream strmIn = Db.class.getResourceAsStream(RES_SCHEMA)) {
            if (strmIn == null)
                throw new IOException("no " + RES_SCHEMA + " in the artifact");
            return new String(strmIn.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private static String digest(String textIn) {
        try {
            byte[] bytesDigest = MessageDigest.getInstance("SHA-256").digest(textIn.getBytes(StandardCharsets.UTF_8));
            StringBuilder sbHex = new StringBuilder(bytesDigest.length * 2);
            for (byte bDigest : bytesDigest) {
                sbHex.append(Character.forDigit((bDigest >> 4) & 0xF, 16));
                sbHex.append(Character.forDigit(bDigest & 0xF, 16));
            }
            return sbHex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }


    /**
     * Splits the schema on a semicolon at end of line. The schema holds no
     * procedural body and no string literal carrying a semicolon, so this is
     * sufficient and stays readable; if either of those changes, so must this.
     */
    private static List<String> statements(String sqlAll) {
        List<String> lstSql = new ArrayList<>();
        StringBuilder sbOne = new StringBuilder();
        for (String lineSql : sqlAll.split("\n")) {
            String lineTrim = lineSql.strip();
            if (lineTrim.isEmpty() || lineTrim.startsWith("--")) {
                continue;
            }
            sbOne.append(lineSql).append('\n');
            if (lineTrim.endsWith(";")) {
                lstSql.add(sbOne.toString());
                sbOne.setLength(0);
            }
        }
        if (!sbOne.toString().isBlank()) {
            lstSql.add(sbOne.toString());
        }
        return lstSql;
    }
}
