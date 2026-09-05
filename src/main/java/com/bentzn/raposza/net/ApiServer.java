/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

/**
 * The read side. Serves whatever the publisher last wrote into the dataset
 * directory; when that directory is absent it serves a corpus generated at
 * startup, so the endpoints answer before anything has published.
 *
 * Author Claude/bentzn
 */
public final class ApiServer {

    private static final Instant INST_START = Instant.now();

    private static final String ID_FALLBACK = Dataset.newPublicationId(INST_START);


    private ApiServer() {
    }


    /** Binds the configured port and blocks. */
    public static void run() {
        int portHttp = Config.httpPort();
        String nameHost = Config.httpHost();
        String dirStatic = Config.staticDir();
        Javalin.create(cfgApp -> {
            if (dirStatic != null) {
                cfgApp.staticFiles.add(cfgFiles -> {
                    cfgFiles.hostedPath = "/";
                    cfgFiles.directory = dirStatic;
                    cfgFiles.location = Location.EXTERNAL;
                });
            }
            cfgApp.routes.before("/api/*", ApiServer::stamp);
            cfgApp.routes.get("/api/v1/status", ApiServer::status);
            cfgApp.routes.get("/api/v1/networks", ApiServer::networks);
            cfgApp.routes.get("/api/v1/networks/{network}", ApiServer::network);
            cfgApp.routes.get("/api/v1/events", ApiServer::events);
            cfgApp.routes.get("/api/v1/events/{id}", ApiServer::event);
            cfgApp.routes.get("/api/v1/sources", ApiServer::sources);
        }).start(nameHost, portHttp);
    }


    private static void stamp(Context ctx) {
        ctx.header("X-Raposza-Publication", publicationId(current()));
    }


    private static void status(Context ctx) {
        Map<String, Object> mapDs = current();
        Map<String, Object> mapMeta = Dataset.meta(mapDs);
        String stampCreated = String.valueOf(mapMeta.get("createdAt"));
        ctx.json(Dataset.map(
                "service", "raposza-network-feed",
                "buildId", Config.buildId(),
                "content", mapMeta.get("content"),
                "publicationId", mapMeta.get("publicationId"),
                "publishedAt", stampCreated,
                "publicationAgeSeconds", ageSeconds(stampCreated),
                "uptimeSeconds", Duration.between(INST_START, Instant.now()).toSeconds(),
                "sources", mapDs.get("sources")));
    }


    private static void networks(Context ctx) {
        Map<String, Object> mapDs = current();
        ctx.json(Dataset.map("publicationId", publicationId(mapDs), "networks", mapDs.get("networks")));
    }


    private static void network(Context ctx) {
        String nameWanted = ctx.pathParam("network").toUpperCase(Locale.ROOT);
        Map<?, ?> mapFound = find(current().get("networks"), "network", nameWanted);
        if (mapFound == null) {
            notFound(ctx, "unknown network: " + ctx.pathParam("network"));
            return;
        }
        ctx.json(mapFound);
    }


    private static void events(Context ctx) {
        Map<String, Object> mapDs = current();
        ctx.json(Dataset.map("publicationId", publicationId(mapDs), "events", mapDs.get("events")));
    }


    private static void event(Context ctx) {
        Map<?, ?> mapFound = find(current().get("events"), "id", ctx.pathParam("id"));
        if (mapFound == null) {
            notFound(ctx, "unknown event: " + ctx.pathParam("id"));
            return;
        }
        ctx.json(mapFound);
    }


    private static void sources(Context ctx) {
        Map<String, Object> mapDs = current();
        ctx.json(Dataset.map("publicationId", publicationId(mapDs), "sources", mapDs.get("sources")));
    }


    private static Map<String, Object> current() {
        Map<String, Object> mapDs = Dataset.read(Config.datasetDir());
        if (mapDs != null) {
            return mapDs;
        }
        return Dataset.generate(ID_FALLBACK, INST_START);
    }


    private static String publicationId(Map<String, Object> mapDs) {
        return String.valueOf(Dataset.meta(mapDs).get("publicationId"));
    }


    private static Map<?, ?> find(Object objList, String nameField, String valWanted) {
        if (!(objList instanceof List)) {
            return null;
        }
        for (Object objItem : (List<?>) objList) {
            if (objItem instanceof Map) {
                Map<?, ?> mapItem = (Map<?, ?>) objItem;
                if (valWanted.equals(String.valueOf(mapItem.get(nameField)))) {
                    return mapItem;
                }
            }
        }
        return null;
    }


    private static long ageSeconds(String stampIso) {
        try {
            return Duration.between(Instant.parse(stampIso), Instant.now()).toSeconds();
        }
        catch (RuntimeException e) {
            return -1L;
        }
    }


    private static void notFound(Context ctx, String textMessage) {
        ctx.status(404);
        ctx.json(Dataset.map("error", "NOT_FOUND", "message", textMessage));
    }
}
