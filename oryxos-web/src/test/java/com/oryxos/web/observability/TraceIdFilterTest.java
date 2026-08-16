package com.oryxos.web.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

  private final TraceIdFilter filter = new TraceIdFilter();

  @Test
  void addsTraceIdHeaderAndClearsMdc() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

    String traceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    assertThat(traceId).isNotBlank();
    assertThat(UUID.fromString(traceId)).isNotNull();
    assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
  }
}
