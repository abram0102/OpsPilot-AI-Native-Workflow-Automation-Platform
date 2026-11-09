package com.opspilot.storage;

/**
 * An unexpected storage-layer failure (I/O error, misconfiguration) — not a client mistake, maps to
 * the generic 500 handler like any other unanticipated exception.
 */
public class StorageException extends RuntimeException {

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }

  public StorageException(String message) {
    super(message);
  }
}
