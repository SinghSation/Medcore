package com.medcore.platform.observability

import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.aop.ObservedAspect
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [ObservedAspect] so `@Observed` annotations on Spring
 * beans produce spans + metrics against the application's
 * [ObservationRegistry].
 *
 * Micrometer ships the aspect class but deliberately does NOT
 * auto-register it (because `@Observed` is an opt-in feature and
 * the aspect requires AspectJ weaving to be on the classpath).
 * Medcore's `build.gradle.kts` adds `org.aspectj:aspectjweaver` to
 * satisfy the runtime dependency.
 *
 * Without this bean, `@Observed` annotations are silent no-ops.
 */
@Configuration(proxyBeanMethods = false)
class ObservedAspectConfig {

    @Bean
    fun observedAspect(observationRegistry: ObservationRegistry): ObservedAspect =
        ObservedAspect(observationRegistry)
}
