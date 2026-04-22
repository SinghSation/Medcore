package com.medcore.platform.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ProxyAwareClientIpResolverTest {

    @Nested
    inner class EmptyTrustList {
        private val resolver = ProxyAwareClientIpResolver(ObservabilityProperties())

        @Test
        fun `returns remoteAddr and ignores XFF`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "10.0.0.5"
                addHeader("X-Forwarded-For", "1.2.3.4")
            }
            assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5")
        }

        @Test
        fun `returns null when remoteAddr is blank`() {
            val request = MockHttpServletRequest().apply { remoteAddr = "" }
            assertThat(resolver.resolve(request)).isNull()
        }
    }

    @Nested
    inner class TrustedExactPeer {
        private val resolver = ProxyAwareClientIpResolver(
            ObservabilityProperties(
                proxy = ObservabilityProperties.Proxy(trustedProxies = listOf("10.0.0.5")),
            ),
        )

        @Test
        fun `returns first untrusted entry in XFF walked right-to-left`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "10.0.0.5"
                addHeader("X-Forwarded-For", "203.0.113.5, 198.51.100.2, 10.0.0.5")
            }
            // 10.0.0.5 trusted -> skip. 198.51.100.2 untrusted -> return.
            assertThat(resolver.resolve(request)).isEqualTo("198.51.100.2")
        }

        @Test
        fun `falls back to remoteAddr when every XFF entry is trusted`() {
            val resolverAllTrusted = ProxyAwareClientIpResolver(
                ObservabilityProperties(
                    proxy = ObservabilityProperties.Proxy(
                        trustedProxies = listOf("10.0.0.5", "10.0.0.6"),
                    ),
                ),
            )
            val request = MockHttpServletRequest().apply {
                remoteAddr = "10.0.0.5"
                addHeader("X-Forwarded-For", "10.0.0.6, 10.0.0.5")
            }
            assertThat(resolverAllTrusted.resolve(request)).isEqualTo("10.0.0.5")
        }

        @Test
        fun `ignores XFF when immediate peer is not in trusted list`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.99" // not trusted
                addHeader("X-Forwarded-For", "203.0.113.5")
            }
            assertThat(resolver.resolve(request)).isEqualTo("192.0.2.99")
        }
    }

    @Nested
    inner class TrustedCidr {
        private val resolver = ProxyAwareClientIpResolver(
            ObservabilityProperties(
                proxy = ObservabilityProperties.Proxy(trustedProxies = listOf("10.0.0.0/16")),
            ),
        )

        @Test
        fun `matches inside CIDR range`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "10.0.255.200"
                addHeader("X-Forwarded-For", "203.0.113.9, 10.0.50.1")
            }
            // Immediate peer 10.0.255.200 ∈ 10.0.0.0/16 -> consult XFF.
            // 10.0.50.1 ∈ 10.0.0.0/16 (trusted, skip). 203.0.113.9 untrusted -> return.
            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9")
        }

        @Test
        fun `outside CIDR range is not trusted`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.1"
                addHeader("X-Forwarded-For", "203.0.113.9")
            }
            // peer outside /16 -> XFF ignored.
            assertThat(resolver.resolve(request)).isEqualTo("192.0.2.1")
        }
    }

    @Nested
    inner class Malformed {
        private val resolver = ProxyAwareClientIpResolver(
            ObservabilityProperties(
                proxy = ObservabilityProperties.Proxy(trustedProxies = listOf("not-an-ip")),
            ),
        )

        @Test
        fun `malformed trusted entry never matches`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "1.2.3.4"
                addHeader("X-Forwarded-For", "203.0.113.9")
            }
            // "not-an-ip" parses to NeverMatch -> peer untrusted -> XFF ignored.
            assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4")
        }
    }

    @Nested
    inner class PortStripping {
        private val resolver = ProxyAwareClientIpResolver(
            ObservabilityProperties(
                proxy = ObservabilityProperties.Proxy(trustedProxies = listOf("10.0.0.5")),
            ),
        )

        @Test
        fun `strips ipv4 port suffix from XFF entry`() {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "10.0.0.5"
                addHeader("X-Forwarded-For", "203.0.113.9:51234, 10.0.0.5")
            }
            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9")
        }
    }
}
