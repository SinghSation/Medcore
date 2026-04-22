package com.medcore.platform.observability

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils

/**
 * Extracts the originating client IP from an inbound request,
 * respecting `X-Forwarded-For` **only** when the immediate peer is in
 * the configured trusted-proxy list.
 *
 * Closes the 3C carry-forward item for proxy-aware `client_ip`.
 *
 * Behaviour:
 *   - If [ObservabilityProperties.Proxy.trustedProxies] is empty, the
 *     resolver returns [HttpServletRequest.getRemoteAddr] verbatim.
 *     `X-Forwarded-For` is NOT consulted. This is the dev / test
 *     default.
 *   - If a trusted-proxy list is configured, the resolver walks the
 *     XFF chain from right to left (the proxy closest to the
 *     application is the right-most value per RFC 7239 convention).
 *     Each entry is checked against the trusted list; the first
 *     entry that is NOT trusted is returned as the originating
 *     client IP. This is the pattern Spring Cloud Gateway / Nginx
 *     / AWS ALB documentation converges on.
 *   - If every XFF entry is trusted, fall back to the inbound
 *     `getRemoteAddr`. This captures the pathological "all zero-
 *     hop" case without leaking a proxy address as a client IP.
 *
 * Spoof resistance:
 *   - The resolver never reads XFF when `trustedProxies` is empty,
 *     so a client hitting the app directly cannot inject a fake
 *     client IP in XFF and have it trusted.
 *   - When XFF is consulted, the immediate peer ([remoteAddr]) MUST
 *     be in the trusted list; otherwise XFF is ignored entirely.
 *
 * Current-phase scope (3F.1):
 *   - Parses IPv4, IPv6, and CIDR match in [trustedProxies] entries.
 *   - Port suffixes (`1.2.3.4:5678`) are stripped before comparison.
 *   - Does NOT attempt reverse-DNS, hostname-based trust, or egress
 *     forensics. Operators configure IPs / CIDRs directly.
 */
@Component
class ProxyAwareClientIpResolver(
    private val properties: ObservabilityProperties,
) {
    private val trustedMatchers: List<TrustMatcher> =
        properties.proxy.trustedProxies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::buildMatcher)

    fun resolve(request: HttpServletRequest): String? {
        val remote = request.remoteAddr?.takeIf { it.isNotBlank() }

        if (trustedMatchers.isEmpty()) {
            return remote
        }

        if (remote == null || !isTrusted(remote)) {
            // Immediate peer is not a trusted proxy — ignore XFF even
            // if present. This is the spoof-resistant branch.
            return remote
        }

        val xff = request.getHeader(properties.proxy.forwardedForHeader) ?: return remote
        val entries = xff.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return remote

        // Walk right-to-left: the rightmost entry is the proxy closest
        // to the app (prepended last by convention). The first entry
        // that is NOT trusted is the originating client IP.
        for (entry in entries.asReversed()) {
            val ip = stripPort(entry)
            if (ip.isEmpty()) continue
            if (!isTrusted(ip)) {
                return ip
            }
        }
        // Every hop was a trusted proxy — fall back to the immediate
        // peer (last known trusted address).
        return remote
    }

    private fun isTrusted(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val ip = stripPort(candidate)
        val parsed = parseIp(ip) ?: return false
        return trustedMatchers.any { it.matches(parsed) }
    }

    private fun stripPort(value: String): String {
        if (value.isBlank()) return value
        // IPv6 bracketed form: [::1]:8080
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            return if (close > 0) value.substring(1, close) else value
        }
        // IPv4 with single colon (port). Naked IPv6 has multiple
        // colons — leave those intact.
        val firstColon = value.indexOf(':')
        val lastColon = value.lastIndexOf(':')
        if (firstColon >= 0 && firstColon == lastColon) {
            return value.substring(0, firstColon)
        }
        return value
    }

    private fun parseIp(value: String): InetAddress? =
        try {
            InetAddress.getByName(value)
        } catch (_: java.net.UnknownHostException) {
            null
        }

    private fun buildMatcher(entry: String): TrustMatcher {
        if (!StringUtils.hasText(entry)) {
            return TrustMatcher.NeverMatch
        }
        val slash = entry.indexOf('/')
        if (slash >= 0) {
            val network = entry.substring(0, slash)
            val prefix = entry.substring(slash + 1).toIntOrNull()
                ?: return TrustMatcher.NeverMatch
            val addr = parseIp(network) ?: return TrustMatcher.NeverMatch
            return TrustMatcher.Cidr(addr, prefix)
        }
        val addr = parseIp(entry) ?: return TrustMatcher.NeverMatch
        return TrustMatcher.Exact(addr)
    }

    private sealed class TrustMatcher {
        abstract fun matches(candidate: InetAddress): Boolean

        object NeverMatch : TrustMatcher() {
            override fun matches(candidate: InetAddress): Boolean = false
        }

        data class Exact(val address: InetAddress) : TrustMatcher() {
            override fun matches(candidate: InetAddress): Boolean =
                address == candidate
        }

        data class Cidr(val network: InetAddress, val prefix: Int) : TrustMatcher() {
            private val networkBytes = network.address

            override fun matches(candidate: InetAddress): Boolean {
                val candidateBytes = candidate.address
                if (candidateBytes.size != networkBytes.size) return false
                val fullBytes = prefix / 8
                val remainingBits = prefix % 8
                if (fullBytes > networkBytes.size) return false
                for (i in 0 until fullBytes) {
                    if (candidateBytes[i] != networkBytes[i]) return false
                }
                if (remainingBits == 0) return true
                if (fullBytes >= networkBytes.size) return true
                val mask = (0xFF shl (8 - remainingBits)) and 0xFF
                return (candidateBytes[fullBytes].toInt() and mask) ==
                    (networkBytes[fullBytes].toInt() and mask)
            }
        }
    }
}
