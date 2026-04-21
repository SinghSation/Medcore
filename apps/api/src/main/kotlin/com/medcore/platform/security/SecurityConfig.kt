package com.medcore.platform.security

import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.config.MedcoreOidcProperties
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain

// Platform-wide Spring Security configuration. Stateless resource-server
// over /api/**, deny-by-default. Every /api/** path requires a valid OIDC
// JWT; the bearer is validated against the issuer URI from
// MedcoreOidcProperties. There is no "auth disabled in dev" switch
// (Rule 01, ADR-002 §3).
//
// At 3C the resource-server uses AuditingAuthenticationEntryPoint so that
// invalid-bearer 401s emit identity.user.login.failure audit rows
// (ADR-003 §7). Anonymous requests (no Authorization header) still return
// 401 but are NOT audited — see the entry point for rationale.
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun jwtDecoder(oidcProperties: MedcoreOidcProperties): JwtDecoder {
        val decoder = JwtDecoders.fromIssuerLocation(oidcProperties.issuerUri) as NimbusJwtDecoder

        val audience = oidcProperties.audience
        val defaultValidator = JwtValidators.createDefaultWithIssuer(oidcProperties.issuerUri)
        val combined: OAuth2TokenValidator<Jwt> = if (audience.isNullOrBlank()) {
            defaultValidator
        } else {
            org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator(
                defaultValidator,
                AudienceValidator(audience),
            )
        }
        decoder.setJwtValidator(combined)
        return decoder
    }

    @Bean
    fun medcoreJwtAuthenticationConverter(
        oidcProperties: MedcoreOidcProperties,
        principalResolver: PrincipalResolver,
    ): MedcoreJwtAuthenticationConverter =
        MedcoreJwtAuthenticationConverter(oidcProperties, principalResolver)

    @Bean
    fun auditingAuthenticationEntryPoint(auditWriter: AuditWriter): AuthenticationEntryPoint =
        AuditingAuthenticationEntryPoint(auditWriter)

    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        converter: MedcoreJwtAuthenticationConverter,
        auditingEntryPoint: AuthenticationEntryPoint,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(converter)
                }
                oauth2.authenticationEntryPoint(auditingEntryPoint)
            }
            .exceptionHandling { it.authenticationEntryPoint(auditingEntryPoint) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout(Customizer.withDefaults())
        return http.build()
    }
}
