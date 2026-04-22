package com.medcore.platform.persistence

import com.medcore.TestcontainersConfiguration
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Direct unit-style verification of [DatabaseRoleSafetyCheck]'s
 * behavior against both kinds of datasource:
 *
 *   - Connecting as the runtime app role (`medcore_app`, non-super)
 *     → check passes silently.
 *   - Connecting as the container superuser (the migrator role) →
 *     check throws, refusing to start the application.
 *
 * The Spring context for this test boots normally (so the in-app
 * role-check has already run successfully against the @Primary
 * appDataSource = medcore_app). We then re-instantiate the check
 * with each datasource explicitly to exercise both branches.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DatabaseRoleSafetyCheckTest {

    @Autowired
    lateinit var appDataSource: DataSource

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @Test
    fun `safety check passes when connected as medcore_app`() {
        val check = DatabaseRoleSafetyCheck(appDataSource)
        // Should not throw.
        check.verifyNonSuperuserOnStartup()
    }

    @Test
    fun `safety check throws and refuses startup when connected as a superuser`() {
        val check = DatabaseRoleSafetyCheck(adminDataSource)
        val ex = assertThrows(IllegalStateException::class.java) {
            check.verifyNonSuperuserOnStartup()
        }
        assertTrue(
            ex.message!!.contains("REFUSING TO START") &&
                ex.message!!.contains("superuser"),
            "expected explicit refusal naming superuser bypass; got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("ADR-001"),
            "expected the error to point at the governing ADR; got: ${ex.message}",
        )
    }

    @Test
    fun `safety check is wired as an ApplicationStartedEvent listener`() {
        // Defensive regression test against accidental annotation
        // removal. The Kotlin annotation may surface as either
        // `value` or `classes` depending on how the source-level
        // call site was parsed; either is acceptable as long as it
        // names ApplicationStartedEvent.
        val method = DatabaseRoleSafetyCheck::class.java.getMethod(
            "verifyNonSuperuserOnStartup",
        )
        val annotation = method.getAnnotation(
            org.springframework.context.event.EventListener::class.java,
        )
        val declared: Set<Class<*>> =
            (annotation.value.toList() + annotation.classes.toList())
                .map { it.java }
                .toSet()
        assertTrue(
            ApplicationStartedEvent::class.java in declared,
            "verifyNonSuperuserOnStartup must listen for ApplicationStartedEvent; " +
                "@EventListener declares: $declared",
        )
    }
}
