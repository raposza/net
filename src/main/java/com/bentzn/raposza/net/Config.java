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


    /** @return seconds between publications made by the worker */
    public static int publishIntervalSeconds() {
        return Integer.parseInt(env("FEED_PUBLISH_INTERVAL_SECONDS", "60"));
    }


    private static String env(String nameVar, String valDefault) {
        String valEnv = System.getenv(nameVar);
        return valEnv == null || valEnv.isBlank() ? valDefault : valEnv;
    }
}
