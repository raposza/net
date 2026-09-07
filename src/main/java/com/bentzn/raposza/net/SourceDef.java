/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

/**
 * One source definition, as it stands in the repository. A source is pinned by
 * identity, not by url: the id is stable, the url is an attribute of it, and a
 * move is recorded here rather than inferred from a redirect.
 *
 * Authority is policy. A source is promoted by a change to this definition under
 * review, never by discovery.
 *
 * Author Claude/bentzn
 */
public record SourceDef(
        String id,
        String publisher,
        String transport,
        String url,
        String branch,
        String pathPrefix,
        String sourceAuthority,
        String claimPolicy,
        int pollSeconds,
        boolean includePreReleases,
        boolean enabled) {

    /** @return true when this source is collected by walking commits */
    public boolean isGit() {
        return "git".equals(transport);
    }


    /** @return true when this source is one GET of one body */
    public boolean isHttp() {
        return "http".equals(transport);
    }


    /** @return true when a collector exists for this transport */
    public boolean isCollectable() {
        return isGit() || isHttp();
    }


    /**
     * @return the ref the collector resolves; HEAD when no branch is named, which
     *         follows whatever the remote declares as its default
     */
    public String ref() {
        return branch == null || branch.isBlank() ? "HEAD" : "refs/heads/" + branch;
    }
}
