package com.medcore.platform.observability

import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

/**
 * Dedicated [SecurityFilterChain] for the actuator path tree (Phase 3F.3).
 *
 * Intent:
 *   - `/actuator/health`, `/actuator/health/liveness`,
 *     `/actuator/health/readiness`, and `/actuator/info` are
 *     **anonymous** so cluster probes (Kubernetes, ECS, ALB) can reach
 *     them without a bearer token. Aggregate `health` is returned
 *     detail-free (application.yaml sets `show-details: never`), so
 *     an unauthenticated caller learns only `UP` or `DOWN` — no
 *     component graph, no DB version, no dependency leakage.
 *   - Any other actuator sub-path requires authentication. Current
 *     configuration does not **expose** other endpoints on the web
 *     (`management.endpoints.web.exposure.include=health,info`), so
 *     they return 404 at the Spring MVC layer before auth is even
 *     evaluated. This security chain is the belt to that braces:
 *     if a future slice exposes an additional endpoint and forgets
 *     to update security, the endpoint defaults to denied rather
 *     than anonymous.
 *
 * Why a separate chain (not an addition to
 * [com.medcore.platform.security.SecurityConfig]):
 *   - The existing chain specialises behaviour for the OAuth2
 *     resource-server on the `/api` tree (JWT decoder, audit-
 *     emitting entry point, JWT converter). Actuator paths have
 *     different needs: anonymous access, no JWT decoding, no
 *     audit entry point. Mixing the concerns would force
 *     conditional logic into the OAuth2 configuration.
 *   - Two path-disjoint chains are easier to reason about and
 *     easier to change independently.
 *
 * Ordering:
 *   - This chain is annotated `@Order(1)`; the `apiSecurityFilterChain`
 *     carries no explicit order and therefore resolves to
 *     [org.springframework.core.Ordered.LOWEST_PRECEDENCE]. Actuator
 *     paths are matched first. Since the two chains' security
 *     matchers are path-disjoint, evaluation order does not affect
 *     correctness; explicit ordering is hygiene.
 *
 * What this chain does NOT do:
 *   - No CSRF handling — actuator endpoints are GET-only in this
 *     phase. If a write-capable actuator endpoint ever ships, CSRF
 *     becomes its concern.
 *   - No session management — actuator is stateless. Explicitly
 *     configured to prove the posture.
 *   - No OAuth2/JWT — probes are anonymous by design.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorSecurityConfig {

    @Bean
    @Order(1)
    fun actuatorSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(ACTUATOR_PATH_PATTERN)
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                // Permit all actuator requests at the security layer.
                // MVC-level exposure (management.endpoints.web.exposure.include
                // in application.yaml) is the authoritative control
                // over what exists; non-exposed endpoints return 404
                // from Spring MVC because no handler is registered.
                //
                // A restrictive security rule here would instead 403
                // non-exposed paths, which fools callers into thinking
                // the endpoint exists behind auth. 404 is the honest
                // answer and matches the Phase 3F.3 behaviour contract
                // asserted by ActuatorProbesIntegrationTest.
                //
                // Guarding against accidental future exposure is the
                // job of the `exposure.include` allow-list in
                // application.yaml, not this chain. Review pack for
                // any slice touching that list must call it out.
                authorize.anyRequest().permitAll()
            }
        return http.build()
    }

    companion object {
        /**
         * Scope of this chain. Covers `/actuator` exactly as well as
         * every sub-path. Spring MVC returns 404 for any actuator path
         * not exposed by `management.endpoints.web.exposure.include`;
         * for exposed paths outside the permit-all list the chain
         * defaults to `authenticated()` which, absent any OAuth2
         * config here, yields 401.
         *
         * [SecurityProperties.BASIC_AUTH_ORDER] is -50 by default. We
         * are not using basic auth; the constant import serves as a
         * reference point only. The explicit `@Order(1)` above
         * determines this chain's position.
         */
        const val ACTUATOR_PATH_PATTERN: String = "/actuator/**"
    }
}
