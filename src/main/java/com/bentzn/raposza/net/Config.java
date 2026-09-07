/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration, entirely from the environment. Nothing is read from a file and
 * nothing is compiled in, so the same artifact runs in every environment.
 *
 * Author Claude/bentzn
 */
public final class Config {

    private Config() {
    }


    /** @return the port the api binds; the range 30000-32000 is reserved for this service */
    public static int httpPort() {
        return Integer.parseInt(env("FEED_HTTP_PORT", "30000"));
    }


    /**
     * Interface the api binds. The default accepts connections from anywhere,
     * which is what a local run and a container both need. Behind a reverse
     * proxy set it to 127.0.0.1 so the port cannot be reached from outside the
     * machine even if the firewall is wrong.
     *
     * @return the bind address
     */
    public static String httpHost() {
        return env("FEED_HTTP_HOST", "0.0.0.0");
    }


    /** @return the identifier of the build being served, reported by the status endpoint */
    public static String buildId() {
        return env("FEED_BUILD_ID", "unknown");
    }


    /** @return the directory the publisher writes and the api reads */
    public static Path datasetDir() {
        return Paths.get(env("FEED_DATASET_DIR", "dataset"));
    }


    /**
     * Directory of the web assets. When set, the api serves them itself, which is
     * the local case. Behind a reverse proxy it is unset and the proxy serves them.
     *
     * @return the directory, or null
     */
    public static String staticDir() {
        String dirStatic = System.getenv("FEED_STATIC_DIR");
        return dirStatic == null || dirStatic.isBlank() ? null : dirStatic;
    }


    /**
     * Which environment this instance is. Reported by the status endpoint so a
     * consumer that has reached the wrong host can tell, and so the web page can
     * check the name against the one it inferred from the hostname.
     *
     * @return the environment name; "local" when nothing set it
     */
    public static String environment() {
        return env("FEED_ENVIRONMENT", "local");
    }


    /**
     * Directory holding the embedded database file. It is a derived index over
     * the evidence store and the journal, so losing it costs a rebuild and
     * nothing else.
     *
     * @return the directory, default ./db
     */
    public static Path dbDir() {
        return Paths.get(env("FEED_DB_DIR", "db"));
    }


    /**
     * The jdbc url. The default is an embedded H2 file under the database
     * directory, so an instance needs no database server and no credentials to
     * run; setting it explicitly is for pointing at another file.
     *
     * @return the url
     */
    public static String dbUrl() {
        String urlSet = optional("FEED_DB_URL");
        if (urlSet != null)
            return urlSet;
        return "jdbc:h2:file:" + dbDir().toAbsolutePath().normalize().resolve("feed")
                + ";DB_CLOSE_ON_EXIT=FALSE";
    }


    /** @return the database user; embedded H2 authenticates nothing */
    public static String dbUser() {
        return env("FEED_DB_USER", "feed");
    }


    /** @return the database password; empty for an embedded file */
    public static String dbPassword() {
        String valSet = optional("FEED_DB_PASSWORD");
        return valSet == null ? "" : valSet;
    }


    /**
     * Root of the evidence store: retrieved bodies, content-addressed. Local to
     * the host that runs the collectors.
     *
     * @return the directory, default ./evidence
     */
    public static Path evidenceDir() {
        return Paths.get(env("FEED_EVIDENCE_DIR", "evidence"));
    }


    /**
     * Root of the git mirrors the collector keeps, one bare repository per
     * source. It is a cache, not evidence: deleting it costs a re-clone and
     * nothing else.
     *
     * @return the directory, default ./git
     */
    public static Path gitDir() {
        return Paths.get(env("FEED_GIT_DIR", "git"));
    }


    /**
     * Root of the poll journal: one appended line per retrieval attempt. With
     * the evidence store it is what the index is rebuilt from, so it is backed
     * up with the same seriousness.
     *
     * @return the directory, default ./journal
     */
    public static Path journalDir() {
        return Paths.get(env("FEED_JOURNAL_DIR", "journal"));
    }


    /**
     * Whether this instance ACQUIRES. Only the environment that owns the record
     * collects: the evidence and journal roots are per environment, so four
     * collectors would poll every source four times for four copies of one
     * answer and split the observed record four ways.
     *
     * The default is false, so an environment acquires only where something says
     * so explicitly. A new environment that nobody configured stays a consumer.
     *
     * @return true when the worker should poll sources
     */
    public static boolean collectEnabled() {
        return Boolean.parseBoolean(env("FEED_COLLECT", "false"));
    }


    /** @return seconds between publications made by the worker */
    public static int publishIntervalSeconds() {
        return Integer.parseInt(env("FEED_PUBLISH_INTERVAL_SECONDS", "60"));
    }


    private static String env(String nameVar, String valDefault) {
        String valEnv = System.getenv(nameVar);
        return valEnv == null || valEnv.isBlank() ? valDefault : valEnv;
    }


    private static String optional(String nameVar) {
        String valEnv = System.getenv(nameVar);
        return valEnv == null || valEnv.isBlank() ? null : valEnv;
    }
}
