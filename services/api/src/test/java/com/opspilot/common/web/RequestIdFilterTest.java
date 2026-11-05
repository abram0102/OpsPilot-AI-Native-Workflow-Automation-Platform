package com.opspilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Fast, Docker/DB-independent unit tests for {@link RequestIdFilter}. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAndEchoesARequestIdWhenCallerSuppliesNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(generated);
        verify(chain).doFilter(request, response);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void reusesAnInboundRequestIdHeaderInsteadOfGeneratingOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "caller-supplied-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("caller-supplied-id-123");
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("caller-supplied-id-123");
    }

    @Test
    void clearsMdcEvenWhenDownstreamThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        RuntimeException boom = new RuntimeException("downstream failure");
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            assertThat(MDC.get("requestId")).isNotNull();
                            throw boom;
                        })
                .when(chain)
                .doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (Exception caught) {
            assertThat(caught).isSameAs(boom);
        }

        assertThat(MDC.get("requestId")).isNull();
    }
}
