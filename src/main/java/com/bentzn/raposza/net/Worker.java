/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
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

    private static GitPublish pubGit;

    private static boolean isPubResolved;


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
        GitPublish pubStart = publisher();
        System.out.println("worker " + Config.environment() + ": git publication "
                + (pubStart == null ? "off" : "to " + pubStart.spec().urlRemote()));
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
     * from the evidence store and the journal; then reads every banked body a
     * normalizer has not read yet. A deleted database file is therefore a
     * recoverable state and not a loss, and so is an index built with another
     * schema, which is dropped and rebuilt the same way.
     */
    static void openIndex() {
        try (Connection conn = Db.connection()) {
            if (Db.schema(conn)) {
                System.out.println("index schema changed: the index was dropped and is rebuilt from evidence and"
                        + " journal");
            }
            if (Db.isEmpty(conn)) {
                Rebuild.Result res = Rebuild.run(conn, Config.evidenceDir(), Config.journalDir(), Sources.load());
                if (res.cntAttempt() > 0 || res.cntMissing() > 0) {
                    System.out.println("rebuilt index from journal: " + res.cntAttempt() + " attempts, "
                            + res.cntObservation() + " observations, " + res.cntMissing() + " bodies missing");
                }
            }
            Normalize.pass(conn, Config.evidenceDir());
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
        Map<String, Object> mapDs = Dataset.generate(idPublication, instNow, index());
        try {
            Dataset.write(Config.datasetDir(), mapDs);
        }
        catch (IOException e) {
            System.err.println("publication failed: " + e);
            return false;
        }
        System.out.println("published " + idPublication + " to "
                + Config.datasetDir().toAbsolutePath().normalize());
        GitPublish pubNow = publisher();
        if (pubNow != null) {
            pubNow.turn(mapDs);
        }
        return true;
    }


    /**
     * The git channel, resolved once. A channel that is switched off or has
     * nowhere to push resolves to null and is not asked again.
     *
     * @return the channel, or null
     */
    private static GitPublish publisher() {
        if (!isPubResolved) {
            pubGit = GitPublish.fromConfig();
            isPubResolved = true;
        }
        return pubGit;
    }


    /**
     * The index read once, for one publication: the source list with the state
     * the index holds, and every event it derived. Both come from one connection
     * so a publication cannot carry a source list from one moment and events
     * from another.
     *
     * @return what the index yielded, or null when it cannot be read, which
     *         publishes the registry alone and says UNAVAILABLE rather than
     *         presenting an empty index as an observed one
     */
    private static Dataset.Index index() {
        try (Connection conn = Db.connection()) {
            return new Dataset.Index(Sources.published(conn, Sources.load()), Events.published(conn));
        }
        catch (SQLException | IOException e) {
            System.err.println("index unavailable, publishing the registry alone: " + e);
            return null;
        }
    }
}
