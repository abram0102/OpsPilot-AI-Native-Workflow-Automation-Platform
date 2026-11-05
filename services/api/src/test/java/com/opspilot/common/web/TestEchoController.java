package com.opspilot.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller used solely to exercise {@link GlobalExceptionHandler}
 * (malformed JSON, bean validation) in {@link GlobalExceptionHandlerTest}.
 * Never shipped in the production jar — it lives under src/test, not
 * src/main, and no business endpoints exist yet at this ticket (T02).
 */
@RestController
class TestEchoController {

    @PostMapping("/__test/echo")
    EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    record EchoRequest(@NotBlank String name) {}
}
