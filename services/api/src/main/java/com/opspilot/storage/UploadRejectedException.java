package com.opspilot.storage;

import com.opspilot.common.web.BadRequestException;

/**
 * The actual uploaded bytes failed validation against what was declared (size exceeded, magic bytes
 * don't match an allowed type, or look like an executable). Extends {@link BadRequestException} so
 * it maps to {@code 400 VALIDATION_ERROR} with no new handler needed.
 */
public class UploadRejectedException extends BadRequestException {

  public UploadRejectedException(String message) {
    super(message);
  }
}
