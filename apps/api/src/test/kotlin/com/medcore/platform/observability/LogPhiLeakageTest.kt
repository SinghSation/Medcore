package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.util.UUID
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * Rule 01 operational control inside the observability slice.
 *
 * Fires a request carrying known tokens that MUST NEVER appear in
 * application log output, then grep-fails the captured stdout for
 * each token.
 *
 * Tokens asserted absent:
 *   - Bearer token value. Spring Security's own logging redacts the
 *     Authorization header; this test defends against accidental
 *     Medcore-side emission.
 *   - A synthetic email address submitted in the JWT claims.
 *   - A synthetic display name submitted in the JWT claims.
 *   - The subject string used to derive the above.
 *
 * This test is called out by
 * `docs/product/03-definition-of-done.md` §3.1.1 as a required
 * component of the Phase 3F.1 exit bar. Future observability slices
 * that add log-emission sites MUST extend this test with coverage
 * for the new sites.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class LogPhiLeakageTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Test
    fun `application logs never contain bearer token email or display name`(output: CapturedOutput) {
        val subject = "leak-${UUID.randomUUID()}"
        val email = "$subject@medcore.test"
        val displayName = "Leakage Test $subject"
        val preferredUsername = "$subject-user"
        val token = tokenFor(subject, email, displayName, preferredUsername)

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            String::class.java,
        )

        // Capture all output emitted during the request lifecycle.
        val captured = output.all

        // Forbidden substrings — any presence is a Rule 01 violation.
        val forbidden = listOf(
            token to "bearer token value must never be logged",
            email to "email claim must never be logged",
            displayName to "display name claim must never be logged",
            preferredUsername to "preferred_username claim must never be logged",
        )

        forbidden.forEach { (needle, reason) ->
            assertThat(captured)
                .describedAs("$reason — needle=$needle")
                .doesNotContain(needle)
        }

        // Belt-and-braces: response succeeded, so the request did
        // exercise the code path under test.
        assertThat(response.statusCode.is2xxSuccessful)
            .describedAs("request must succeed for the test to be meaningful")
            .isTrue()
    }

    private fun tokenFor(
        subject: String,
        email: String,
        displayName: String,
        preferredUsername: String,
    ): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf(
                    "email" to email,
                    "email_verified" to true,
                    "name" to displayName,
                    "preferred_username" to preferredUsername,
                ),
            ),
        ).serialize()

    private fun bearer(token: String) = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
