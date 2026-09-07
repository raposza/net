/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The evidence store: retrieved bodies, content-addressed by sha256 and never
 * overwritten.
 *
 * Storing by content hash makes an unchanged retrieval free and makes a changed
 * one impossible to miss. It also means a body cannot be edited in place:
 * different bytes are a different key, so an observation that cites a key cites
 * exactly the bytes that were retrieved.
 *
 * The store root is passed in rather than read from the environment, so a
 * caller can address a store other than the configured one; Config.evidenceDir
 * is what the roles pass.
 *
 * Author Claude/bentzn
 */
public final class Evidence {

    private Evidence() {
    }


    /**
     * Stores a body. Storing the same bytes twice writes once and returns the
     * same key both times.
     *
     * @param dirRoot the store root
     * @param bytesBody the retrieved body
     * @return the storage key, of the form sha256/&lt;hex&gt;
     * @throws IOException when the store cannot be written
     */
    public static String put(Path dirRoot, byte[] bytesBody) throws IOException {
        String keyStorage = key(bytesBody);
        Path fileTarget = path(dirRoot, keyStorage);
        if (Files.exists(fileTarget))
            return keyStorage;
        Files.createDirectories(fileTarget.getParent());
        Path fileTmp = Files.createTempFile(fileTarget.getParent(), ".put", ".tmp");
        try {
            Files.write(fileTmp, bytesBody);
            Files.move(fileTmp, fileTarget, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(fileTmp);
        }
        return keyStorage;
    }


    /**
     * @param bytesBody the body to address
     * @return the storage key the body would be stored under, without storing it
     */
    public static String key(byte[] bytesBody) {
        return "sha256/" + hex(sha256(bytesBody));
    }


    /**
     * @param dirRoot the store root
     * @param keyStorage a key returned by put or key
     * @return true when the store holds that body
     */
    public static boolean has(Path dirRoot, String keyStorage) {
        return Files.exists(path(dirRoot, keyStorage));
    }


    /**
     * @param dirRoot the store root
     * @param keyStorage a key returned by put or key
     * @return the stored bytes
     * @throws IOException when the body is absent or unreadable
     */
    public static byte[] get(Path dirRoot, String keyStorage) throws IOException {
        return Files.readAllBytes(path(dirRoot, keyStorage));
    }


    /**
     * @param dirRoot the store root
     * @param keyStorage a key returned by put or key
     * @return where that body sits in the store
     */
    public static Path path(Path dirRoot, String keyStorage) {
        return dirRoot.resolve(keyStorage);
    }


    private static byte[] sha256(byte[] bytesBody) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytesBody);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }


    private static String hex(byte[] bytesDigest) {
        StringBuilder sbHex = new StringBuilder(bytesDigest.length * 2);
        for (byte bDigest : bytesDigest) {
            sbHex.append(Character.forDigit((bDigest >> 4) & 0xF, 16));
            sbHex.append(Character.forDigit(bDigest & 0xF, 16));
        }
        return sbHex.toString();
    }
}
