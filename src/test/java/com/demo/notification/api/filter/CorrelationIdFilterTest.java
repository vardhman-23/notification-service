package com.demo.notification.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Filter preserves existing X-Correlation-ID from request header and cleans up MDC")
    void testPreservesExistingCorrelationId() throws ServletException, IOException {
        String existingId = "client-trace-12345";
        when(request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).thenReturn(existingId);
        when(request.getRequestURI()).thenReturn("/api/v1/notifications");

        filter.doFilterInternal(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isEqualTo(existingId);
        });

        verify(response).setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Filter generates fresh UUID when X-Correlation-ID is missing or blank")
    void testGeneratesFreshCorrelationIdWhenMissing() throws ServletException, IOException {
        when(request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/notifications");

        filter.doFilterInternal(request, response, (req, res) -> {
            String mdcVal = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            assertThat(mdcVal).isNotNull().isNotBlank();
        });

        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Filter generates fresh UUID when X-Correlation-ID is empty string")
    void testGeneratesFreshCorrelationIdWhenBlank() throws ServletException, IOException {
        when(request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).thenReturn("   ");
        when(request.getRequestURI()).thenReturn("/api/v1/notifications");

        filter.doFilterInternal(request, response, (req, res) -> {
            String mdcVal = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            assertThat(mdcVal).isNotNull().isNotBlank();
        });

        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }
}

