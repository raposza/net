/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.util.List;

/**
 * Reads one banked body of one source into claims.
 *
 * A normalizer is a pure function: the same bytes give the same claims, in the
 * same order, every time. It reaches nothing outside the body, so what it
 * produces is derivable from the evidence store alone and a rebuild produces it
 * again. No LLM output is in this path; parsing is deterministic.
 *
 * The identifier carries a version. A change to what a normalizer produces is a
 * new version, and the claims of the old version are replaced, never mixed in.
 *
 * Author Claude/bentzn
 */
public interface Normalizer {

    /** @return the identifier with its version, for example SvOperationsScheduleNormalizer@1 */
    String id();


    /** @return the source whose bodies this normalizer reads */
    String sourceId();


    /**
     * @param bytesBody one banked body, exactly as it was retrieved
     * @return the claims it makes, sorted by subject reference and field
     * @throws Refused when the body is not the shape this normalizer reads. No
     *         claim is made from a body that is only partly understood
     */
    List<Claim> normalize(byte[] bytesBody) throws Refused;


    /** A body a normalizer will not read, with the reason. */
    final class Refused extends Exception {

        private static final long serialVersionUID = 1L;


        /**
         * @param textReason what is wrong with the body
         */
        public Refused(String textReason) {
            super(textReason);
        }


        /**
         * @param textReason what is wrong with the body
         * @param thrCause the parser failure underneath
         */
        public Refused(String textReason, Throwable thrCause) {
            super(textReason, thrCause);
        }
    }
}
