package com.opspilot.common.security;

import com.opspilot.common.web.ApiErrorResponse;
import com.opspilot.common.web.RequestIdSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/** The filter-chain-level counterpart to {@link JsonAuthenticationEntryPoint}, for 403s. */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ApiErrorResponse body =
        ApiErrorResponse.of(
            "FORBIDDEN",
            "You cannot perform this action.",
            RequestIdSupport.resolve(request),
            List.of());
    objectMapper.writeValue(response.getWriter(), body);
  }
}
