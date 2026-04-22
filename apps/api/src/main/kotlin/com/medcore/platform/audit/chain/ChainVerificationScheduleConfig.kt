package com.medcore.platform.audit.chain

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Activates Spring's scheduling infrastructure and binds
 * [ChainVerificationProperties] for the chain-verification slice.
 *
 * Scope is deliberately narrow — `@EnableScheduling` is enabled here
 * rather than at [com.medcore.MedcoreApiApplication] so that the
 * scheduling concern travels with the code that uses it. Future
 * slices that introduce additional `@Scheduled` methods may either:
 *
 *   (a) declare their own `@Configuration @EnableScheduling` class
 *       alongside their scheduled component, OR
 *   (b) hoist `@EnableScheduling` to a platform-wide config via an
 *       ADR when three or more slices demonstrate a consistent need.
 *
 * Option (a) is the current preference. Premature hoisting creates a
 * global concern that every future consumer inherits whether they
 * want it or not.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ChainVerificationProperties::class)
class ChainVerificationScheduleConfig
