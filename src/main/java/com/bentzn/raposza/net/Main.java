/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

/**
 * Entry point. One jar, five roles: api, worker, collect, replay, rebuild.
 *
 * Author Claude/bentzn
 */
public final class Main {

    private Main() {
    }


    /**
     * @param argsMain the role, and nothing else
     * @throws Exception whatever the selected role throws
     */
    public static void main(String[] argsMain) throws Exception {
        String nameRole = argsMain.length > 0 ? argsMain[0] : "";
        switch (nameRole) {
            case "api":
                ApiServer.run();
                break;
            case "worker":
                Worker.run();
                break;
            case "collect":
                if (Collect.once() > 0) {
                    System.exit(1);
                }
                break;
            case "replay":
                if (!Worker.publishOnce()) {
                    System.exit(1);
                }
                break;
            case "rebuild":
                Rebuild.force();
                break;
            default:
                System.err.println(
                        "usage: java -jar raposza-network-feed.jar <api|worker|collect|replay|rebuild>");
                System.exit(2);
        }
    }
}
