/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The write side. Generates a corpus and publishes it into the dataset
 * directory, once for the replay role and on a fixed interval for the worker.
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
        while (true) {
            publishOnce();
            TimeUnit.SECONDS.sleep(secInterval);
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
            Dataset.write(Config.datasetDir(), Dataset.generate(idPublication, instNow));
            System.out.println("published " + idPublication + " to "
                    + Config.datasetDir().toAbsolutePath().normalize());
            return true;
        }
        catch (IOException e) {
            System.err.println("publication failed: " + e);
            return false;
        }
    }
}
