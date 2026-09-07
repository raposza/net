/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.List;

/**
 * The outcome of one retrieval attempt: what happened, how long it took, and
 * what it produced.
 *
 * A poll that produced nothing is still a poll, and an outcome that is a failure
 * is still data about the source. Every collector returns one of these and none
 * of them throw.
 *
 * Author Claude/bentzn
 */
public record Poll(
        String outcome,
        String detail,
        long msDuration,
        String cursor,
        List<Banked> lstBanked) {

    /** @return an attempt that produced nothing, with the reason */
    public static Poll of(String nameOutcome, String textDetail, long msDuration, String cursor) {
        return new Poll(nameOutcome, textDetail, msDuration, cursor, List.of());
    }
}
