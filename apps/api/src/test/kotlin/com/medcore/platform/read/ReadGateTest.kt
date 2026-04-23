package com.medcore.platform.read

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalStatus
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import com.medcore.platform.write.WriteTxHook
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

/**
 * Unit coverage for [ReadGate] (Phase 4A.4).
 *
 * Uses hand-rolled test doubles (no Mockito). Verifies the
 * five-step pipeline:
 *
 *   1. Policy is checked BEFORE the tx opens.
 *   2. Policy denial → `onDenied` audit + re-throw; tx never
 *      opens; handler never runs.
 *   3. On success: txHook runs → handler runs → auditor
 *      `onSuccess` runs — in that order, inside the tx.
 *   4. If `onSuccess` throws, the tx rolls back and the throw
 *      propagates.
 *   5. Denial-audit failure logs ERROR but the original
 *      denial still propagates (caller still gets 403).
 */
class ReadGateTest {

    private val principal = MedcorePrincipal(
        userId = UUID.randomUUID(),
        issuerSubject = IssuerSubject(issuer = "http://localhost/", subject = "alice"),
        email = null,
        emailVerified = true,
        displayName = null,
        preferredUsername = null,
        status = PrincipalStatus.ACTIVE,
        issuedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(60),
    )
    private val context = WriteContext(principal = principal)

    @Test
    fun `success path — policy, hook, execute, onSuccess run in order`() {
        val trace = mutableListOf<String>()
        val policy = ReadAuthzPolicy<String> { _, _ -> trace += "policy" }
        val hook = WriteTxHook { trace += "hook" }
        val auditor = TracingAuditor<String, Int>(trace)
        val txManager = FakeTxManager(trace)

        val gate = ReadGate(policy, auditor, txManager, hook)
        val result = gate.apply("cmd", context) { _ ->
            trace += "execute"
            42
        }

        assertThat(result).isEqualTo(42)
        assertThat(trace).containsExactly(
            "policy",
            "getTransaction(readOnly=false)",
            "hook",
            "execute",
            "onSuccess",
            "commit",
        )
    }

    @Test
    fun `policy denial — onDenied emits, handler + onSuccess never run, tx never opens`() {
        val trace = mutableListOf<String>()
        val policy = ReadAuthzPolicy<String> { _, _ ->
            trace += "policy-throw"
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
        val auditor = TracingAuditor<String, Int>(trace)
        val txManager = FakeTxManager(trace)

        val gate = ReadGate(policy, auditor, txManager, null)

        var handlerRan = false
        assertThatThrownBy {
            gate.apply("cmd", context) { _ ->
                handlerRan = true
                42
            }
        }.isInstanceOf(WriteAuthorizationException::class.java)

        assertThat(handlerRan).isFalse()
        assertThat(trace).containsExactly("policy-throw", "onDenied:insufficient_authority")
        assertThat(auditor.onDeniedReason).isEqualTo(WriteDenialReason.INSUFFICIENT_AUTHORITY)
    }

    @Test
    fun `onSuccess throws — transaction rolls back, handler result never delivered`() {
        val trace = mutableListOf<String>()
        val policy = ReadAuthzPolicy<String> { _, _ -> }
        val auditor = object : ReadAuditor<String, Int> {
            override fun onSuccess(command: String, result: Int, context: WriteContext) {
                trace += "onSuccess-throw"
                throw RuntimeException("audit write failed")
            }
            override fun onDenied(command: String, context: WriteContext, reason: WriteDenialReason) =
                error("should not be called")
        }
        val txManager = FakeTxManager(trace)

        val gate = ReadGate(policy, auditor, txManager, null)

        assertThatThrownBy {
            gate.apply("cmd", context) { _ ->
                trace += "execute"
                99
            }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("audit write failed")

        assertThat(trace).containsExactly(
            "getTransaction(readOnly=false)",
            "execute",
            "onSuccess-throw",
            "rollback",
        )
    }

    @Test
    fun `denial audit failure logs but does NOT swallow the original denial`() {
        val policy = ReadAuthzPolicy<String> { _, _ ->
            throw WriteAuthorizationException(WriteDenialReason.NOT_A_MEMBER)
        }
        val auditor = object : ReadAuditor<String, Int> {
            override fun onSuccess(command: String, result: Int, context: WriteContext) =
                error("should not be called")
            override fun onDenied(command: String, context: WriteContext, reason: WriteDenialReason) {
                throw RuntimeException("audit chain unreachable")
            }
        }
        val txManager = FakeTxManager(mutableListOf())

        val gate = ReadGate(policy, auditor, txManager, null)

        // The ORIGINAL denial is what propagates — the audit
        // failure is logged at ERROR but swallowed so the
        // caller still gets a WriteAuthorizationException (→
        // 403), not the audit's RuntimeException (→ 500).
        assertThatThrownBy {
            gate.apply("cmd", context) { 0 }
        }.isInstanceOf(WriteAuthorizationException::class.java)
    }

    @Test
    fun `null txHook — success path still runs`() {
        val trace = mutableListOf<String>()
        val policy = ReadAuthzPolicy<String> { _, _ -> }
        val auditor = TracingAuditor<String, Int>(trace)
        val txManager = FakeTxManager(trace)

        val gate = ReadGate(policy, auditor, txManager, null)
        val result = gate.apply("cmd", context) { _ ->
            trace += "execute"
            7
        }

        assertThat(result).isEqualTo(7)
        assertThat(trace).containsExactly(
            "getTransaction(readOnly=false)",
            "execute",
            "onSuccess",
            "commit",
        )
    }

    // ---- test doubles ----

    /**
     * Tracks auditor calls without using a mocking library.
     */
    private class TracingAuditor<CMD, R>(private val trace: MutableList<String>) :
        ReadAuditor<CMD, R> {
        var onDeniedReason: WriteDenialReason? = null

        override fun onSuccess(command: CMD, result: R, context: WriteContext) {
            trace += "onSuccess"
        }

        override fun onDenied(command: CMD, context: WriteContext, reason: WriteDenialReason) {
            onDeniedReason = reason
            trace += "onDenied:${reason.code}"
        }
    }

    /**
     * Minimal PlatformTransactionManager that records calls.
     * Commits and rollbacks are observed via trace entries;
     * nothing actually persists.
     */
    private class FakeTxManager(private val trace: MutableList<String>) :
        PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            val readOnly = definition?.isReadOnly == true
            trace += "getTransaction(readOnly=$readOnly)"
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            trace += "commit"
        }

        override fun rollback(status: TransactionStatus) {
            trace += "rollback"
        }
    }
}
