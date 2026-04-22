package com.medcore.platform.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * Behaviour-first verification of Phase 3F.3 actuator exposure and
 * security.
 *
 * Assertions:
 *   - `/actuator/health`, `/actuator/health/liveness`,
 *     `/actuator/health/readiness`, `/actuator/info` all return 200
 *     without any bearer token (anonymous).
 *   - `/actuator/health` aggregate payload is detail-free: a single
 *     `status` key only, no `components` / `details`.
 *   - Exposed endpoints return JSON content type.
 *   - `/actuator/env`, `/actuator/metrics`, `/actuator/prometheus`,
 *     `/actuator/beans` return **404** — they are not exposed at the
 *     MVC layer. This guards against accidental future exposure.
 *   - The `/api` tree still requires authentication: anonymous
 *     access returns 401 (not 200, not 404).
 *
 * Deliberate non-assertions:
 *   - No test for `status: DOWN` — the tests run against a healthy
 *     Testcontainers Postgres, so readiness is always UP. DOWN
 *     semantics are covered by Spring's own unit tests of
 *     `DataSourceHealthIndicator`; reproducing them here would
 *     duplicate framework testing without exercising Medcore logic.
 *   - No payload-shape assertion on `/actuator/info` beyond content
 *     type — the phase intentionally ships with no `info` producers,
 *     so the payload is `{}`. Future slices may add build-info; a
 *     producer-specific test belongs with that slice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ActuatorProbesIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    private val mapper = ObjectMapper()

    @Test
    fun `liveness probe is anonymous and returns status UP as JSON`() {
        val response = rest.getForEntity("/actuator/health/liveness", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJsonContentType(response.headers.contentType)
        val body = mapper.readTree(response.body)
        assertThat(body.get("status").asText()).isEqualTo("UP")
    }

    @Test
    fun `readiness probe is anonymous and returns status UP as JSON`() {
        val response = rest.getForEntity("/actuator/health/readiness", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJsonContentType(response.headers.contentType)
        val body = mapper.readTree(response.body)
        assertThat(body.get("status").asText()).isEqualTo("UP")
    }

    @Test
    fun `aggregate health is anonymous, JSON, and detail-free`() {
        val response = rest.getForEntity("/actuator/health", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJsonContentType(response.headers.contentType)
        val body = mapper.readTree(response.body)
        assertThat(body.get("status").asText()).isEqualTo("UP")
        // Rule: `show-details: never` — payload MUST NOT expose the
        // component graph, per-indicator names, DB version, or any
        // dependency detail to an unauthenticated caller.
        //
        // Spring Boot does emit a `groups` field listing configured
        // probe group NAMES ("liveness", "readiness") when
        // `management.endpoint.health.probes.enabled` is true. Those
        // names are a static, known-good set with no infrastructure
        // leakage; permitted.
        //
        // The anti-list below is exhaustive for Spring Boot 3.4
        // aggregate-health responses with `show-details: never`.
        val fieldNames = body.fieldNames().asSequence().toList()
        assertThat(fieldNames)
            .describedAs("aggregate health must not expose component graph or indicator detail")
            .doesNotContain("components", "details")
        assertThat(fieldNames)
            .describedAs("aggregate health field whitelist")
            .allSatisfy { name -> assertThat(name).isIn("status", "groups") }
    }

    @Test
    fun `info endpoint is anonymous and returns JSON`() {
        val response = rest.getForEntity("/actuator/info", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertJsonContentType(response.headers.contentType)
        // Body is `{}` by default (no info producers wired in 3F.3).
        val body = mapper.readTree(response.body)
        assertThat(body.isObject).isTrue()
    }

    @Test
    fun `env endpoint is not exposed and returns 404`() {
        val response = rest.getForEntity("/actuator/env", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/actuator/env must NOT be exposed")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `metrics endpoint is not exposed and returns 404`() {
        val response = rest.getForEntity("/actuator/metrics", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/actuator/metrics must NOT be exposed (Prometheus / traces land in 3F.2)")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `prometheus endpoint is not exposed and returns 404`() {
        val response = rest.getForEntity("/actuator/prometheus", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/actuator/prometheus must NOT be exposed (3F.2 territory)")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `beans endpoint is not exposed and returns 404`() {
        val response = rest.getForEntity("/actuator/beans", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/actuator/beans must NOT be exposed")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `mappings endpoint is not exposed and returns 404`() {
        val response = rest.getForEntity("/actuator/mappings", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/actuator/mappings must NOT be exposed")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `api still requires authentication (regression)`() {
        // Anonymous access to /api/** must still 401. Guards against
        // the new actuator chain accidentally loosening /api/**.
        val response = rest.getForEntity("/api/v1/me", String::class.java)
        assertThat(response.statusCode)
            .describedAs("/api/** must remain authenticated under the new chain")
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun assertJsonContentType(contentType: MediaType?) {
        assertThat(contentType).isNotNull()
        // Spring Boot Actuator uses application/vnd.spring-boot.actuator.v3+json
        // for its canonical actuator responses. Accepting either that
        // or plain application/json keeps the test stable across
        // minor Spring Boot revisions.
        val type = contentType!!
        assertThat(type.type).isEqualTo("application")
        assertThat(type.subtype).contains("json")
    }
}
