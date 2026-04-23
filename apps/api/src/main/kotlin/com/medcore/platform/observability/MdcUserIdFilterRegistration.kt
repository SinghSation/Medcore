package com.medcore.platform.observability

import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [MdcUserIdFilter] in the filter chain at
 * `SecurityProperties.DEFAULT_FILTER_ORDER + 5` — after Spring
 * Security has populated the security context, before the tenancy
 * filter at `+10` consumes the resolved principal.
 *
 * Same double-registration precautions as
 * [RequestIdFilterRegistration] and
 * [com.medcore.tenancy.context.TenantContextFilterRegistration]:
 * the filter is NOT a `@Component`, and the explicit
 * [FilterRegistrationBean] wrapping the single instance prevents
 * Spring Boot's servlet auto-configuration from synthesising a
 * duplicate registration.
 */
@Configuration(proxyBeanMethods = false)
class MdcUserIdFilterRegistration {

    @Bean
    fun mdcUserIdFilter(): MdcUserIdFilter = MdcUserIdFilter()

    @Bean
    fun mdcUserIdFilterServletRegistration(
        filter: MdcUserIdFilter,
    ): FilterRegistrationBean<MdcUserIdFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.order = SecurityProperties.DEFAULT_FILTER_ORDER + 5
        // Phase 4A.5 — extend coverage to the FHIR namespace.
        registration.addUrlPatterns("/api/*", "/fhir/*")
        registration.setName("mdcUserIdFilter")
        return registration
    }
}
