package com.opspilot.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * Resolves the current request's ID for use outside {@link RequestIdFilter}
 * itself (e.g. error responses). Falls back to a freshly generated ID if the
 * filter did not run for this request (for example in a narrow test slice
 * that disables servlet filters) so callers never have to null-check.
 */
public final class RequestIdSupport {

    private RequestIdSupport() {}

    public static String resolve(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return attribute != null ? attribute.toString() : UUID.randomUUID().toString();
    }
}
