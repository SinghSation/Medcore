package com.medcore.platform.observability

import jakarta.servlet.FilterChain
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestIdFilterTest {

    private val properties = ObservabilityProperties()
    private val filter = RequestIdFilter(properties)

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `generates uuidv4 when inbound header is absent`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()
        val captured = captureMdcDuringFilter(request, response)

        val responseHeader = response.getHeader(properties.requestId.headerName)
        assertThat(responseHeader).isNotNull()
        assertThat(UUID.fromString(responseHeader)).isNotNull() // parses as UUID
        assertThat(captured).isEqualTo(responseHeader)
    }

    @Test
    fun `accepts well-formed inbound uuid`() {
        val inbound = "550e8400-e29b-41d4-a716-446655440000"
        val request = MockHttpServletRequest("GET", "/api/v1/me").apply {
            addHeader(properties.requestId.headerName, inbound)
        }
        val response = MockHttpServletResponse()
        val captured = captureMdcDuringFilter(request, response)

        assertThat(response.getHeader(properties.requestId.headerName)).isEqualTo(inbound)
        assertThat(captured).isEqualTo(inbound)
    }

    @Test
    fun `discards malformed inbound header and mints fresh uuid`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me").apply {
            addHeader(properties.requestId.headerName, "not-a-uuid")
        }
        val response = MockHttpServletResponse()
        val captured = captureMdcDuringFilter(request, response)

        val header = response.getHeader(properties.requestId.headerName)!!
        assertThat(header).isNotEqualTo("not-a-uuid")
        assertThat(UUID.fromString(header)).isNotNull()
        assertThat(captured).isEqualTo(header)
    }

    @Test
    fun `discards overlong inbound header and mints fresh uuid`() {
        val overlong = "a".repeat(properties.requestId.maxLength + 1)
        val request = MockHttpServletRequest("GET", "/api/v1/me").apply {
            addHeader(properties.requestId.headerName, overlong)
        }
        val response = MockHttpServletResponse()
        val captured = captureMdcDuringFilter(request, response)

        val header = response.getHeader(properties.requestId.headerName)!!
        assertThat(header).isNotEqualTo(overlong)
        assertThat(UUID.fromString(header)).isNotNull()
        assertThat(captured).isEqualTo(header)
    }

    @Test
    fun `discards blank inbound header and mints fresh uuid`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me").apply {
            addHeader(properties.requestId.headerName, "   ")
        }
        val response = MockHttpServletResponse()
        val captured = captureMdcDuringFilter(request, response)

        assertThat(UUID.fromString(response.getHeader(properties.requestId.headerName))).isNotNull()
        assertThat(captured).isNotBlank()
    }

    @Test
    fun `does not echo response header when echoOnResponse is false`() {
        val props = ObservabilityProperties(
            requestId = ObservabilityProperties.RequestId(echoOnResponse = false),
        )
        val noEchoFilter = RequestIdFilter(props)
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        noEchoFilter.doFilter(request, response, passthroughChain())

        assertThat(response.getHeader(props.requestId.headerName)).isNull()
    }

    @Test
    fun `clears MDC after filter returns`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, passthroughChain())

        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
    }

    @Test
    fun `clears MDC when downstream filter throws`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()
        val throwingChain = FilterChain { _, _ -> error("downstream failure") }

        runCatching { filter.doFilter(request, response, throwingChain) }

        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
    }

    private fun captureMdcDuringFilter(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
    ): String {
        var captured: String? = null
        val chain = FilterChain { _, _ ->
            captured = MDC.get(MdcKeys.REQUEST_ID)
        }
        filter.doFilter(request, response, chain)
        return requireNotNull(captured) { "chain never ran" }
    }

    private fun passthroughChain(): FilterChain = FilterChain { _, _ -> /* no-op */ }
}
