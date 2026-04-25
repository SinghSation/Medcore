package com.medcore.clinical.patient.api

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate

/**
 * End-to-end coverage for
 * `DELETE /api/v1/tenants/{slug}/patients/{patientId}/identifiers/{identifierId}`
 * (Phase 4A.3).
 *
 * Covers:
 *   - Happy path (soft-delete via valid_to) — 204 + audit row
 *   - Idempotent DELETE on already-revoked — 204, no audit row
 *   - Cross-tenant identifierId — 404
 *   - MEMBER denial — 403 + denial audit row
 *   - Identifier belongs to wrong patient — 404 (ID-smuggling defence)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RevokePatientIdentifierIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.problem")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER revokes identifier — 204, valid_to set, audit row`() {
        val (patientId, identifierId) = seedPatientWithIdentifier()

        val response = delete("alice", "acme-health", patientId, identifierId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val validTo = jdbc.queryForObject(
            "SELECT valid_to FROM clinical.patient_identifier WHERE id = ?",
            java.sql.Timestamp::class.java, identifierId,
        )
        assertThat(validTo)
            .describedAs("revoke must set valid_to to a timestamp")
            .isNotNull()

        val audit = auditRows("clinical.patient.identifier.revoked")
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["reason"])
            .isEqualTo("intent:clinical.patient.identifier.revoke|type:DRIVERS_LICENSE")
        assertThat(audit.single()["resource_id"]).isEqualTo(identifierId.toString())
    }

    @Test
    fun `idempotent DELETE on already-revoked — 204, NO additional audit row`() {
        val (patientId, identifierId) = seedPatientWithIdentifier()

        // First revoke — writes audit row
        val first = delete("alice", "acme-health", patientId, identifierId)
        assertThat(first.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // Second revoke — must be idempotent (204) and emit NO new audit row
        val second = delete("alice", "acme-health", patientId, identifierId)
        assertThat(second.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val audit = auditRows("clinical.patient.identifier.revoked")
        assertThat(audit)
            .describedAs("no-op suppression — second revoke must not emit")
            .hasSize(1)
    }

    @Test
    fun `cross-tenant identifierId — 404`() {
        val (patientId, _) = seedPatientWithIdentifier()
        val otherIdentifierId = UUID.randomUUID()
        val response = delete("alice", "acme-health", patientId, otherIdentifierId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `identifier belongs to different patient — 404 (ID-smuggling defence)`() {
        // Seed two patients with identifiers. Caller tries to revoke
        // patient B's identifier via patient A's URL.
        val (patientAId, _identifierA) = seedPatientWithIdentifier()

        // Create a second patient + identifier in the same tenant.
        val secondCreate = postPatient("alice", "acme-health", nameFamily = "Hopper")
        val patientBId = UUID.fromString(
            (secondCreate.body!!["data"] as Map<*, *>)["id"] as String,
        )
        val secondIdentifier = postIdentifier("alice", "acme-health", patientBId)
        val identifierBId = UUID.fromString(
            (secondIdentifier.body!!["data"] as Map<*, *>)["id"] as String,
        )

        // Caller uses patient A's URL but identifier B's id.
        val response = delete("alice", "acme-health", patientAId, identifierBId)
        assertThat(response.statusCode)
            .describedAs("identifier belongs to different patient — 404 (no existence leak)")
            .isEqualTo(HttpStatus.NOT_FOUND)

        // Verify patient B's identifier was NOT revoked
        val validTo = jdbc.queryForObject(
            "SELECT valid_to FROM clinical.patient_identifier WHERE id = ?",
            java.sql.Timestamp::class.java, identifierBId,
        )
        assertThat(validTo).isNull()
    }

    @Test
    fun `MEMBER cannot revoke — 403 + denial audit row`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        val identifierResp = postIdentifier("owner", "acme-health", patientId)
        val identifierId = UUID.fromString(
            (identifierResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = delete("alice", "acme-health", patientId, identifierId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = auditRows("authz.write.denied").filter {
            (it["reason"] as String).startsWith("intent:clinical.patient.identifier.revoke")
        }
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:clinical.patient.identifier.revoke|denial:insufficient_authority")
        assertThat(denial.single()["resource_id"])
            .describedAs("denial row carries the target identifier UUID (URL pinpoints it)")
            .isEqualTo(identifierId.toString())
    }

    // --- helpers ---

    private fun seedPatientWithIdentifier(): Pair<UUID, UUID> {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val createResp = postPatient("alice", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        val identifierResp = postIdentifier("alice", "acme-health", patientId)
        val identifierId = UUID.fromString(
            (identifierResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        return patientId to identifierId
    }

    private fun provisionUser(subject: String): UUID {
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearerOnly(tokenFor(subject))),
            Map::class.java,
        )
        return jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = ?",
            UUID::class.java,
            subject,
        )!!
    }

    private fun seedTenant(slug: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            id, slug, "Display $slug",
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedMembership(tenant: UUID, user: UUID, role: String) {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenant, user, role,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun postPatient(
        subject: String,
        slug: String,
        nameFamily: String = "Lovelace",
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """{"nameGiven":"Ada","nameFamily":"$nameFamily","birthDate":"1960-05-15","administrativeSex":"female"}""",
                headers,
            ),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun postIdentifier(
        subject: String,
        slug: String,
        patientId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/identifiers",
            HttpMethod.POST,
            HttpEntity(
                """{"type":"DRIVERS_LICENSE","issuer":"CA","value":"D${UUID.randomUUID().toString().take(7)}"}""",
                headers,
            ),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun delete(
        subject: String,
        slug: String,
        patientId: UUID,
        identifierId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/identifiers/$identifierId",
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    private fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = ?
             ORDER BY recorded_at
            """.trimIndent(),
            action,
        )

    private fun tokenFor(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf(
                    "email" to "$subject@medcore.test",
                    "email_verified" to true,
                    "preferred_username" to "$subject-user",
                    "name" to "User $subject",
                ),
            ),
        ).serialize()

    private fun authJsonHeaders(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
