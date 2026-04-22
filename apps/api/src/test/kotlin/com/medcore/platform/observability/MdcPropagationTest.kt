package com.medcore.platform.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * Unit-level assertions on MDC lifecycle discipline.
 *
 * The integration side (end-to-end correlation through HTTP, filters,
 * and audit) is covered by [RequestIdAuditCorrelationTest]. This test
 * guards against silent drift in the basics:
 *
 *   - MDC is cleared between requests in the same thread (tested
 *     indirectly by confirming `finally` blocks remove the keys in
 *     the three filters — those filters are individually tested in
 *     [RequestIdFilterTest] and [MdcUserIdFilterTest]).
 *   - MDC key names match the [MdcKeys] object (canonical strings
 *     have not drifted).
 *   - MDC supports the concurrent pattern we expect (put / read /
 *     remove in the same thread has expected semantics on this
 *     runtime).
 */
class MdcPropagationTest {

    @AfterEach
    fun clear() {
        MDC.clear()
    }

    @Test
    fun `mdc keys are the expected canonical values`() {
        // Catches accidental key renames that would silently break
        // correlation across filter, audit, and log surfaces.
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("request_id")
        assertThat(MdcKeys.USER_ID).isEqualTo("user_id")
        assertThat(MdcKeys.TENANT_ID).isEqualTo("tenant_id")
    }

    @Test
    fun `put and get round-trip`() {
        MDC.put(MdcKeys.REQUEST_ID, "abc")
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("abc")
        MDC.remove(MdcKeys.REQUEST_ID)
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
    }

    @Test
    fun `clear removes all keys`() {
        MDC.put(MdcKeys.REQUEST_ID, "a")
        MDC.put(MdcKeys.USER_ID, "b")
        MDC.put(MdcKeys.TENANT_ID, "c")

        MDC.clear()

        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull()
        assertThat(MDC.get(MdcKeys.TENANT_ID)).isNull()
    }
}
