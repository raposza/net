/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * are no migrations: a schema that has moved on is not migrated, the file is
 * deleted and the index is rebuilt.
 *
 * Embedded H2 admits ONE writer process. The worker owns it. The api reads the
 * published dataset directory and opens no database at all, which is what makes
 * one file safe across the two services.
 *
 * Author Claude/bentzn
 */
public final class Db {

    private static final String RES_SCHEMA = "/schema.sql";


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
     * Brings the schema into existence. Idempotent: every statement is guarded,
     * so a second run changes nothing.
     *
     * @param conn an open connection
     * @throws SQLException when a statement fails
     * @throws IOException when the schema resource is absent
     */
    public static void schema(Connection conn) throws SQLException, IOException {
        try (Statement stmt = conn.createStatement()) {
            for (String sqlOne : statements(read())) {
                stmt.execute(sqlOne);
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


    private static String read() throws IOException {
        try (InputStream strmIn = Db.class.getResourceAsStream(RES_SCHEMA)) {
            if (strmIn == null)
                throw new IOException("no " + RES_SCHEMA + " in the artifact");
            return new String(strmIn.readAllBytes(), StandardCharsets.UTF_8);
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
