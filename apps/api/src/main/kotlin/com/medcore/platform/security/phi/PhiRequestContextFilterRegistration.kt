package com.medcore.platform.security.phi

import com.medcore.tenancy.context.TenantContext
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [PhiRequestContextFilter] after Spring Security AND
 * after `TenantContextFilter`, before any controller dispatch.
 *
 * Ordering:
 * - `SecurityProperties.DEFAULT_FILTER_ORDER` (Spring Security) — `-100`
 * - `TenantContextFilter` (tenant resolution) — `DEFAULT_FILTER_ORDER + 10`
 * - **`PhiRequestContextFilter` — `DEFAULT_FILTER_ORDER + 20`** (this)
 * - Controller dispatch — far after all above filters
 *
 * Double-registration safety is the same pattern as
 * `TenantContextFilterRegistration`: the filter is NOT a
 * `@Component`, it is exposed only as the [phiRequestContextFilter]
 * bean below, and the explicit [phiRequestContextFilterServletRegistration]
 * wraps that instance so Spring Boot's auto-detection does not
 * synthesize a second registration.
 *
 * `OncePerRequestFilter` is additional defence: even if a
 * duplicate somehow sneaked through, the filter body runs only
 * once per request via the `ALREADY_FILTERED_SUFFIX` marker.
 */
@Configuration(proxyBeanMethods = false)
class PhiRequestContextFilterRegistration {

    @Bean
    fun phiRequestContextFilter(
        phiRequestContextHolder: PhiRequestContextHolder,
        tenantContext: TenantContext,
    ): PhiRequestContextFilter =
        PhiRequestContextFilter(phiRequestContextHolder, tenantContext)

    @Bean
    fun phiRequestContextFilterServletRegistration(
        filter: PhiRequestContextFilter,
    ): FilterRegistrationBean<PhiRequestContextFilter> {
        val registration = FilterRegistrationBean(filter)
        // +20 places this AFTER TenantContextFilter (+10) so the
        // tenant context is resolved before we try to pick up
        // `tenantContext.current()`.
        registration.order = SecurityProperties.DEFAULT_FILTER_ORDER + 20
        registration.addUrlPatterns("/api/*")
        registration.setName("phiRequestContextFilter")
        return registration
    }
}
