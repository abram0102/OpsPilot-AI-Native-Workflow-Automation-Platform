package com.opspilot.storage;

/** Where and how the client should PUT the actual bytes for an upload session. */
public record UploadTarget(String uploadUrl, String uploadMethod) {}
