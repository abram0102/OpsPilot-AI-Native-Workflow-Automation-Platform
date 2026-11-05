package com.opspilot.common.web;

import java.util.List;

/**
 * The stable error envelope every OpsPilot API error response uses, per
 * docs/API_CONTRACT.md: {@code { "error": { "code", "message", "requestId",
 * "details": [] } } }. Never includes a stack trace or internal exception
 * message — see {@link GlobalExceptionHandler}.
 */
public record ApiErrorResponse(ApiError error) {

    public record ApiError(String code, String message, String requestId, List<String> details) {}

    public static ApiErrorResponse of(String code, String message, String requestId, List<String> details) {
        return new ApiErrorResponse(new ApiError(code, message, requestId, details));
    }
}
