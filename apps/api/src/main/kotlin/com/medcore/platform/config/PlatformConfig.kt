package com.medcore.platform.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Registers typed platform configuration holders. Feature modules MUST read
 * platform settings through injected `@ConfigurationProperties` classes, never
 * via `@Value` on feature-level beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MedcoreOidcProperties::class)
class PlatformConfig
