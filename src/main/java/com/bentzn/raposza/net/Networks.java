/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The per-network summary, derived from the published events and from nothing
 * else.
 *
 * NO SOURCE THIS FEED READS REPORTS WHAT A NETWORK IS RUNNING. The schedule
 * states what the operators have said they intend to do, so every value here is
 * SCHEDULED and is named as such: a date that has arrived is not evidence that
 * the upgrade happened, and the running version is not published because
 * nothing observes it. A network is summarised by three statements of the
 * schedule and no fourth:
 *
 * <pre>
 * splice.scheduled*  the latest CONFIRMED NETWORK_UPGRADE that carries a
 *                    version and whose date has arrived
 * splice.minimum*    the latest MINIMUM_SPLICE_VERSION that is not cancelled,
 *                    carries a version, and whose date has arrived
 * next.*             the earliest NETWORK_UPGRADE that is not cancelled and
 *                    whose date is still ahead
 * </pre>
 *
 * "Arrived" is the publication's own UTC date, compared against a date-precision
 * effective.from as text, which is why both are ISO. An upgrade dated today
 * counts as arrived; it may not have run yet, which is the whole reason the
 * field is not called a running version.
 *
 * A withdrawn event states nothing, so it is ignored here while remaining in the
 * published event list with its flag.
 *
 * Three networks are always published, in a fixed order, with null values where
 * the schedule says nothing. A network that vanished when nothing was scheduled
 * would make the dataset directory gain and lose files as the calendar moves,
 * and would leave a consumer unable to tell an unknown network from a quiet one.
 *
 * Author Claude/bentzn
 */
public final class Networks {

    /** The networks published, in publication order. */
    public static final List<String> LST_NETWORK = List.of("DEVNET", "TESTNET", "MAINNET");

    private static final String KIND_UPGRADE = "NETWORK_UPGRADE";

    private static final String KIND_MINIMUM = "MINIMUM_SPLICE_VERSION";

    private static final String KIND_DEPLOYMENT = "NETWORK_DEPLOYMENT";

    private static final String KIND_NODE = "SV_NODE";

    private static final String CONFIRMED = "CONFIRMED";

    private static final String CANCELLED = "CANCELLED";


    private Networks() {
    }


    /**
     * @param lstEvent the published events, as Events.published produced them
     * @param instNow the moment of publication, whose UTC date decides what has
     *        arrived and what is still ahead
     * @param idPublication stamped into every record
     * @param stampNow the publication time in the form every timestamp uses
     * @return one record per known network, in LST_NETWORK order
     */
    public static List<Object> derive(List<Object> lstEvent, Instant instNow, String idPublication,
            String stampNow) {
        String dayToday = LocalDate.ofInstant(instNow, ZoneOffset.UTC).toString();
        List<Object> lstOut = new ArrayList<>();
        for (String nameNetwork : LST_NETWORK) {
            List<Map<String, Object>> lstMine = mine(lstEvent, nameNetwork);
            Map<String, Object> mapScheduled = scheduled(lstMine, dayToday);
            Map<String, Object> mapMinimum = minimum(lstMine, dayToday);
            Map<String, Object> mapNext = next(lstMine, dayToday);
            lstOut.add(Dataset.map(
                    "network", nameNetwork,
                    "splice", Dataset.map(
                            "scheduledVersion", version(mapScheduled, "value"),
                            "scheduledPrecision", version(mapScheduled, "precision"),
                            "scheduledFrom", effective(mapScheduled),
                            "scheduledEventId", id(mapScheduled),
                            "minimumVersion", version(mapMinimum, "value"),
                            "minimumPrecision", version(mapMinimum, "precision"),
                            "minimumFrom", effective(mapMinimum),
                            "minimumEventId", id(mapMinimum)),
                    "next", Dataset.map(
                            "eventId", id(mapNext),
                            "version", version(mapNext, "value"),
                            "status", text(mapNext, "status"),
                            "from", effective(mapNext)),
                    "deployment", deployment(lstMine),
                    "superValidators", nodes(lstMine),
                    "publicationId", idPublication,
                    "updatedAt", stampNow));
        }
        return lstOut;
    }


    /**
     * What the network reports it is RUNNING, as distinct from everything above
     * it, which is what its operators have said they intend.
     *
     * One endpoint per network reports one record, so there is exactly one such
     * event per network and the newest reading of it is the event's current
     * state. Where no such event exists - an environment that has banked none -
     * every field is null, which is the same shape as a network the schedule is
     * silent about.
     *
     * @param lstMine the events of one network
     * @return the deployment record, fields null where nothing reports one
     */
    private static Map<String, Object> deployment(List<Map<String, Object>> lstMine) {
        Map<String, Object> mapFound = null;
        for (Map<String, Object> mapEvent : lstMine) {
            if (KIND_DEPLOYMENT.equals(text(mapEvent, "kind"))) {
                mapFound = mapEvent;
            }
        }
        Map<String, Object> mapDeploy = sub(mapFound, "deployment");
        return Dataset.map(
                "version", version(mapFound, "value"),
                "precision", version(mapFound, "precision"),
                "svVersion", text(mapDeploy, "svVersion"),
                "migrationId", text(mapDeploy, "migrationId"),
                "serialId", text(mapDeploy, "serialId"),
                "chainIdSuffix", text(mapDeploy, "chainIdSuffix"),
                "successorVersion", text(mapDeploy, "successorVersion"),
                "legacyVersion", text(mapDeploy, "legacyVersion"),
                "eventId", id(mapFound));
    }


    /**
     * Every Super Validator node of one network with the version it reports.
     *
     * An organization is connected to a node, not to a network, and the two
     * are not the same fact: a roster carrying more than one version is normal
     * during an upgrade, and a consumer told only the network figure cannot see
     * whether its own node is among the ones that moved.
     *
     * Ordered by name, which is upstream's own identifier for a node, so two
     * publications of one index are identical.
     *
     * @param lstMine the events of one network
     * @return one record per node, empty where none is reported
     */
    private static List<Object> nodes(List<Map<String, Object>> lstMine) {
        List<Map<String, Object>> lstNode = new ArrayList<>();
        for (Map<String, Object> mapEvent : lstMine) {
            if (KIND_NODE.equals(text(mapEvent, "kind"))) {
                lstNode.add(mapEvent);
            }
        }
        lstNode.sort(Comparator.comparing(mapOne -> String.valueOf(ref(mapOne))));
        List<Object> lstOut = new ArrayList<>();
        for (Map<String, Object> mapEvent : lstNode) {
            lstOut.add(Dataset.map(
                    "name", ref(mapEvent),
                    "version", version(mapEvent, "value"),
                    "scan", text(mapEvent, "scan_url"),
                    "eventId", id(mapEvent)));
        }
        return lstOut;
    }


    /**
     * @param mapEvent a published event
     * @return the upstream reference it was keyed by, which for a node is its
     *         name
     */
    private static String ref(Map<String, Object> mapEvent) {
        return text(sub(mapEvent, "upstream"), "ref");
    }


    /**
     * The latest confirmed upgrade that has arrived. A version is required: an
     * upgrade announced without one states no version, and publishing null while
     * an older record does state one would lose the answer rather than report it.
     */
    private static Map<String, Object> scheduled(List<Map<String, Object>> lstMine, String dayToday) {
        Map<String, Object> mapOut = null;
        for (Map<String, Object> mapEvent : lstMine) {
            String dayFrom = effective(mapEvent);
            if (!KIND_UPGRADE.equals(text(mapEvent, "kind")))
                continue;
            if (!CONFIRMED.equals(text(mapEvent, "status")))
                continue;
            if (version(mapEvent, "value") == null || dayFrom == null || dayFrom.compareTo(dayToday) > 0)
                continue;
            if (mapOut == null || dayFrom.compareTo(effective(mapOut)) >= 0) {
                mapOut = mapEvent;
            }
        }
        return mapOut;
    }


    /** The latest minimum version that has arrived and was not cancelled. */
    private static Map<String, Object> minimum(List<Map<String, Object>> lstMine, String dayToday) {
        Map<String, Object> mapOut = null;
        for (Map<String, Object> mapEvent : lstMine) {
            String dayFrom = effective(mapEvent);
            if (!KIND_MINIMUM.equals(text(mapEvent, "kind")))
                continue;
            if (CANCELLED.equals(text(mapEvent, "status")))
                continue;
            if (version(mapEvent, "value") == null || dayFrom == null || dayFrom.compareTo(dayToday) > 0)
                continue;
            if (mapOut == null || dayFrom.compareTo(effective(mapOut)) >= 0) {
                mapOut = mapEvent;
            }
        }
        return mapOut;
    }


    /**
     * The earliest upgrade still ahead. A cancelled record is kept by the source
     * rather than removed, so it is excluded here by its status; a tentative one
     * is published, because the calendar carrying unconfirmed items and
     * confirming them later is exactly the heads-up a consumer wants.
     */
    private static Map<String, Object> next(List<Map<String, Object>> lstMine, String dayToday) {
        Map<String, Object> mapOut = null;
        for (Map<String, Object> mapEvent : lstMine) {
            String dayFrom = effective(mapEvent);
            if (!KIND_UPGRADE.equals(text(mapEvent, "kind")))
                continue;
            if (CANCELLED.equals(text(mapEvent, "status")))
                continue;
            if (dayFrom == null || dayFrom.compareTo(dayToday) <= 0)
                continue;
            if (mapOut == null || dayFrom.compareTo(effective(mapOut)) < 0) {
                mapOut = mapEvent;
            }
        }
        return mapOut;
    }


    /** Every event of one network that is not withdrawn. */
    private static List<Map<String, Object>> mine(List<Object> lstEvent, String nameNetwork) {
        List<Map<String, Object>> lstOut = new ArrayList<>();
        for (Object objEvent : lstEvent) {
            if (!(objEvent instanceof Map))
                continue;
            Map<String, Object> mapEvent = cast(objEvent);
            if ("true".equals(String.valueOf(mapEvent.get("withdrawn"))))
                continue;
            if (nameNetwork.equals(text(mapEvent, "network"))) {
                lstOut.add(mapEvent);
            }
        }
        return lstOut;
    }


    private static String id(Map<String, Object> mapEvent) {
        return text(mapEvent, "id");
    }


    private static String effective(Map<String, Object> mapEvent) {
        return text(sub(mapEvent, "effective"), "from");
    }


    private static String version(Map<String, Object> mapEvent, String nameField) {
        return text(sub(mapEvent, "version"), nameField);
    }


    private static Map<String, Object> sub(Map<String, Object> mapEvent, String nameField) {
        if (mapEvent == null)
            return null;
        Object objSub = mapEvent.get(nameField);
        return objSub instanceof Map ? cast(objSub) : null;
    }


    private static String text(Map<String, Object> mapAny, String nameField) {
        if (mapAny == null)
            return null;
        Object objValue = mapAny.get(nameField);
        return objValue == null ? null : String.valueOf(objValue);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object objAny) {
        return (Map<String, Object>) objAny;
    }
}
