/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

/**
 * One retrieved representation, banked. Transport-neutral: a git commit and an
 * http body are the same thing here, differing only in which fields are set.
 *
 * This is what the journal writes and what a rebuild reads back, so it carries
 * everything the index needs. Nothing is recovered by parsing the body itself:
 * a rebuild must not depend on being able to interpret evidence, only on having
 * it.
 *
 * Author Claude/bentzn
 */
public record Banked(
        String keyStorage,
        String shaContent,
        String mediaType,
        String revision,
        String upstreamAt,
        Integer httpStatus,
        String etag,
        String lastModified) {
}
