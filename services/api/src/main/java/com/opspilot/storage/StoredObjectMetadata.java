package com.opspilot.storage;

/** The object's actual size and SHA-256 checksum, as read back from storage itself. */
public record StoredObjectMetadata(long sizeBytes, String sha256) {}
