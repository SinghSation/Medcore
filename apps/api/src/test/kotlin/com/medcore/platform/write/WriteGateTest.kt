package com.medcore.platform.write

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalStatus
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
 * Unit coverage for [WriteGate] behavioural invariants (Phase 3J,
 * ADR-007). Pure in-memory test doubles; no Spring context. Every
 * path is verifiable at unit speed.
 */
class WriteGateTest {

    private data class TestCommand(val value: String)
    private data class TestResult(val value: String)

    private val principal = MedcorePrincipal(
        userId = UUID.randomUUID(),
        issuerSubject = IssuerSubject(issuer = "http://localhost/x", subject = "s"),
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
    fun `apply runs validator then policy then execute then audit-success in order`() {
        val order = mutableListOf<String>()
        val validator = WriteValidator<TestCommand> { order += "validate" }
        val policy = AuthzPolicy<TestCommand> { _, _ -> order += "authz" }
        val auditor = RecordingAuditor<TestCommand, TestResult>(onSuccessTag = "audit", order = order)

        val gate = WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = NoopTxManager(),
            validator = validator,
        )

        val result = gate.apply(TestCommand("hello"), context) {
            order += "execute"
            TestResult("world")
        }

        assertThat(result).isEqualTo(TestResult("world"))
        assertThat(order).containsExactly("validate", "authz", "execute", "audit")
    }

    @Test
    fun `validator failure short-circuits before policy runs`() {
        val policyCalls = mutableListOf<TestCommand>()
        val auditor = RecordingAuditor<TestCommand, TestResult>()
        val validator = WriteValidator<TestCommand> { throw IllegalArgumentException("invalid") }
        val policy = AuthzPolicy<TestCommand> { cmd, _ -> policyCalls += cmd }

        val gate = WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = NoopTxManager(),
            validator = validator,
        )

        assertThatThrownBy {
            gate.apply(TestCommand("x"), context) { TestResult("never") }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(policyCalls).isEmpty()
        assertThat(auditor.successCalls).isEmpty()
        assertThat(auditor.deniedCalls).isEmpty()
    }

    @Test
    fun `authz denial emits onDenied audit and re-throws without running execute`() {
        val auditor = RecordingAuditor<TestCommand, TestResult>()
        val policy = AuthzPolicy<TestCommand> { _, _ ->
            throw WriteAuthorizationException(WriteDenialReason.NOT_A_MEMBER)
        }

        val gate = WriteGate(policy = policy, auditor = auditor, txManager = NoopTxManager())

        var executeRan = false
        assertThatThrownBy {
            gate.apply(TestCommand("x"), context) {
                executeRan = true
                TestResult("never")
            }
        }.isInstanceOf(WriteAuthorizationException::class.java)
            .extracting { (it as WriteAuthorizationException).reason }
            .isEqualTo(WriteDenialReason.NOT_A_MEMBER)

        assertThat(executeRan).isFalse()
        assertThat(auditor.deniedCalls).hasSize(1)
        assertThat(auditor.deniedCalls.single().reason).isEqualTo(WriteDenialReason.NOT_A_MEMBER)
        assertThat(auditor.successCalls).isEmpty()
    }

    @Test
    fun `denial audit throwing does not swallow the original denial`() {
        val auditor = object : WriteAuditor<TestCommand, TestResult> {
            override fun onSuccess(c: TestCommand, r: TestResult, ctx: WriteContext) = Unit
            override fun onDenied(c: TestCommand, ctx: WriteContext, reason: WriteDenialReason) {
                throw RuntimeException("audit down")
            }
        }
        val policy = AuthzPolicy<TestCommand> { _, _ ->
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }

        val gate = WriteGate(policy = policy, auditor = auditor, txManager = NoopTxManager())

        // The original WriteAuthorizationException wins. Caller still
        // gets a predictable 403; audit-down surfaces in logs only.
        assertThatThrownBy {
            gate.apply(TestCommand("x"), context) { TestResult("never") }
        }.isInstanceOf(WriteAuthorizationException::class.java)
            .extracting { (it as WriteAuthorizationException).reason }
            .isEqualTo(WriteDenialReason.INSUFFICIENT_AUTHORITY)
    }

    @Test
    fun `execute exception propagates and onSuccess is never called`() {
        val auditor = RecordingAuditor<TestCommand, TestResult>()
        val policy = AuthzPolicy<TestCommand> { _, _ -> /* allow */ }

        val gate = WriteGate(policy = policy, auditor = auditor, txManager = NoopTxManager())

        assertThatThrownBy {
            gate.apply(TestCommand("x"), context) { throw IllegalStateException("boom") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(auditor.successCalls).isEmpty()
    }

    @Test
    fun `onSuccess exception inside transaction propagates to caller`() {
        val auditor = object : WriteAuditor<TestCommand, TestResult> {
            override fun onSuccess(c: TestCommand, r: TestResult, ctx: WriteContext) {
                throw IllegalStateException("audit failed")
            }
            override fun onDenied(c: TestCommand, ctx: WriteContext, reason: WriteDenialReason) = Unit
        }
        val policy = AuthzPolicy<TestCommand> { _, _ -> /* allow */ }

        val gate = WriteGate(policy = policy, auditor = auditor, txManager = NoopTxManager())

        assertThatThrownBy {
            gate.apply(TestCommand("x"), context) { TestResult("ok") }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("audit failed")
    }

    @Test
    fun `validator-less gate skips validation step and still enforces order`() {
        val order = mutableListOf<String>()
        val policy = AuthzPolicy<TestCommand> { _, _ -> order += "authz" }
        val auditor = RecordingAuditor<TestCommand, TestResult>(onSuccessTag = "audit", order = order)

        val gate = WriteGate(policy = policy, auditor = auditor, txManager = NoopTxManager(), validator = null)

        val result = gate.apply(TestCommand("x"), context) {
            order += "execute"
            TestResult("ok")
        }

        assertThat(result).isEqualTo(TestResult("ok"))
        assertThat(order).containsExactly("authz", "execute", "audit")
    }

    // --- Test doubles ---

    private class RecordingAuditor<CMD, R>(
        private val onSuccessTag: String? = null,
        private val order: MutableList<String>? = null,
    ) : WriteAuditor<CMD, R> {
        val successCalls: MutableList<SuccessCall<CMD, R>> = mutableListOf()
        val deniedCalls: MutableList<DeniedCall<CMD>> = mutableListOf()
        override fun onSuccess(command: CMD, result: R, context: WriteContext) {
            successCalls += SuccessCall(command, result, context)
            if (onSuccessTag != null) order?.add(onSuccessTag)
        }
        override fun onDenied(command: CMD, context: WriteContext, reason: WriteDenialReason) {
            deniedCalls += DeniedCall(command, context, reason)
        }
        data class SuccessCall<CMD, R>(val cmd: CMD, val result: R, val context: WriteContext)
        data class DeniedCall<CMD>(val cmd: CMD, val context: WriteContext, val reason: WriteDenialReason)
    }

    private class NoopTxManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }
}
