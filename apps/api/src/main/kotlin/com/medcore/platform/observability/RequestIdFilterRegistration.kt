package com.medcore.platform.observability

import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [RequestIdFilter] explicitly, BEFORE Spring Security's
 * filter chain.
 *
 * Filter order:
 *   Spring Security's delegating filter proxy registers at
 *   [SecurityProperties.DEFAULT_FILTER_ORDER] = -100.
 *   [RequestIdFilter] is placed at `DEFAULT_FILTER_ORDER - 10` = -110
 *   so the correlation id is populated BEFORE any security filter
 *   runs. This is load-bearing:
 *     - 401 responses from
 *       [com.medcore.platform.security.AuditingAuthenticationEntryPoint]
 *       emit an `identity.user.login.failure` audit row which now
 *       carries the same request id as the response header.
 *     - Log lines generated during security-filter execution
 *       (token parsing, validation, JWK fetch) are correlated.
 *     - The tenancy filter at `DEFAULT_FILTER_ORDER + 10` inherits
 *       the MDC value naturally.
 *
 * Double-registration safety:
 *   - [RequestIdFilter] is NOT a `@Component`; the only instance is
 *     the bean declared below. Spring Boot's
 *     `ServletContextInitializerBeans#addAdaptableBeans` detects the
 *     explicit [FilterRegistrationBean] wrapping that instance and
 *     skips its default auto-registration.
 *   - Extending `OncePerRequestFilter` is belt-and-braces defence
 *     against any stray duplicate registration in the future.
 *
 * A corresponding test
 * ([com.medcore.platform.observability.RequestIdFilterRegistrationTest])
 * asserts there is exactly one `FilterRegistrationBean<RequestIdFilter>`
 * and that its order matches the value configured below.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObservabilityProperties::class)
class RequestIdFilterRegistration {

    @Bean
    fun requestIdFilter(properties: ObservabilityProperties): RequestIdFilter =
        RequestIdFilter(properties)

    @Bean
    fun requestIdFilterServletRegistration(
        filter: RequestIdFilter,
    ): FilterRegistrationBean<RequestIdFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.order = SecurityProperties.DEFAULT_FILTER_ORDER - 10
        registration.addUrlPatterns("/*")
        registration.setName("requestIdFilter")
        return registration
    }
}
