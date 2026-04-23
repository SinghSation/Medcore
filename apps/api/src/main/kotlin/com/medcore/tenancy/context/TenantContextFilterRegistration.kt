package com.medcore.tenancy.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.tenancy.TenantMembershipLookup
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [TenantContextFilter] after Spring Security's filter chain so
 * the security context is populated before tenant resolution runs
 * (requirement 19 in the slice spec).
 *
 * Spring Security's filter is registered at
 * [SecurityProperties.DEFAULT_FILTER_ORDER] (= -100); we place the
 * tenancy filter immediately after it with `+ 10` slack so any future
 * security-adjacent filter Spring Boot adds between them still lands in
 * the correct relative position.
 *
 * Double-registration safety:
 *
 * Spring Boot's servlet auto-configuration walks the context for any
 * `jakarta.servlet.Filter` bean and wraps it in a synthesised
 * `FilterRegistrationBean` unless the developer provides one. The risk
 * we avoid here is the filter running TWICE per request — once under
 * the synthesised registration and once under ours.
 *
 * Precautions in place:
 *
 *   1. [TenantContextFilter] is NOT a `@Component`; it is only available
 *      as the [tenantContextFilter] bean defined below.
 *   2. The explicit [tenantContextFilterServletRegistration] wraps that
 *      same instance, which Spring Boot's auto-configuration detects and
 *      does NOT duplicate (see
 *      `ServletContextInitializerBeans#addAdaptableBeans`).
 *   3. Extending `OncePerRequestFilter` is a belt-and-braces defence:
 *      even if a duplicate registration ever slipped through, the filter
 *      body would still execute only once per request thanks to the
 *      `ALREADY_FILTERED_SUFFIX` attribute check.
 *
 * A test (`TenantContextFilterRegistrationTest`) asserts there is
 * exactly one `FilterRegistrationBean<TenantContextFilter>` and that its
 * order is the one configured here.
 */
@Configuration(proxyBeanMethods = false)
class TenantContextFilterRegistration {

    @Bean
    fun tenantContextFilter(
        tenantContext: TenantContext,
        membershipLookup: TenantMembershipLookup,
        objectMapper: ObjectMapper,
        auditWriter: AuditWriter,
    ): TenantContextFilter =
        TenantContextFilter(tenantContext, membershipLookup, objectMapper, auditWriter)

    @Bean
    fun tenantContextFilterServletRegistration(
        filter: TenantContextFilter,
    ): FilterRegistrationBean<TenantContextFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.order = SecurityProperties.DEFAULT_FILTER_ORDER + 10
        // Phase 4A.5 — extend coverage to the FHIR namespace so
        // tenant resolution applies to /fhir/r4/** endpoints too.
        registration.addUrlPatterns("/api/*", "/fhir/*")
        registration.setName("tenantContextFilter")
        return registration
    }
}
