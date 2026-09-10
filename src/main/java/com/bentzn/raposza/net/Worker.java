/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The write side. On each turn it polls the sources whose interval has elapsed
 * and then publishes: the worker interval is how often the schedule is looked
 * at, and the source interval is how often that source is actually retrieved.
 *
 * Publication happens whether or not collection did, so a source that is down
 * never stops the dataset being served.
 *
 * Only the environment that owns the record acquires. Elsewhere the worker
 * publishes and polls nothing, so the test environments exercise the publication
 * path without competing for the sources or splitting the observed history.
 *
 * Author Claude/bentzn
 */
public final class Worker {

    private Worker() {
    }


    /**
     * Publishes forever.
     *
     * @throws InterruptedException when the process is asked to stop
     */
    public static void run() throws InterruptedException {
        int secInterval = Config.publishIntervalSeconds();
        boolean isCollecting = Config.collectEnabled();
        System.out.println("worker " + Config.environment() + ": "
                + (isCollecting ? "acquiring and publishing" : "publishing only, not acquiring"));
        openIndex();
        while (true) {
            if (isCollecting) {
                Collect.due();
            }
            publishOnce();
            TimeUnit.SECONDS.sleep(secInterval);
        }
    }


    /**
     * Brings the index into existence and, when it holds nothing, rebuilds it
     * from the evidence store and the journal. A deleted database file is
     * therefore a recoverable state and not a loss.
     */
    static void openIndex() {
        try (Connection conn = Db.connection()) {
            Db.schema(conn);
            if (!Db.isEmpty(conn)) {
                return;
            }
            Rebuild.Result res = Rebuild.run(conn, Config.evidenceDir(), Config.journalDir(), Sources.load());
            if (res.cntAttempt() > 0 || res.cntMissing() > 0) {
                System.out.println("rebuilt index from journal: " + res.cntAttempt() + " attempts, "
                        + res.cntObservation() + " observations, " + res.cntMissing() + " bodies missing");
            }
        }
        catch (SQLException | IOException e) {
            System.err.println("index unavailable: " + e);
        }
    }


    /**
     * Publishes exactly one publication; a failure leaves the previous one in place.
     *
     * @return true when the dataset directory now holds the new publication
     */
    public static boolean publishOnce() {
        Instant instNow = Instant.now();
        String idPublication = Dataset.newPublicationId(instNow);
        try {
            Dataset.write(Config.datasetDir(), Dataset.generate(idPublication, instNow, sourceList()));
            System.out.println("published " + idPublication + " to "
                    + Config.datasetDir().toAbsolutePath().normalize());
            return true;
        }
        catch (IOException e) {
            System.err.println("publication failed: " + e);
            return false;
        }
    }


    /**
     * @return the registry with the state the index holds for each source, or
     *         null when the index cannot be read, which publishes the registry
     *         alone rather than a stale list
     */
    private static List<Object> sourceList() {
        try (Connection conn = Db.connection()) {
            return Sources.published(conn, Sources.load());
        }
        catch (SQLException | IOException e) {
            System.err.println("source state unavailable, publishing the registry alone: " + e);
            return null;
        }
    }
}
