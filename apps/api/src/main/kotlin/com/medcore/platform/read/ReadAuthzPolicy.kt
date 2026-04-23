package com.medcore.platform.read

import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext

/**
 * Authorization policy for a single READ command type [CMD]
 * (Phase 4A.4).
 *
 * Sister type to
 * [com.medcore.platform.write.AuthzPolicy] with the same shape
 * and throw semantics — a distinct interface is retained for
 * semantic clarity: implementations live in their module's
 * read path, typed separately so grep and tooling distinguish
 * read-path from write-path authz.
 *
 * The four-dimensional check (authenticated / role / authority /
 * scope) from ADR-007 §4.2 applies symmetrically to reads.
 *
 * [check] returns normally on ALLOW and throws
 * [WriteAuthorizationException] on DENY — reusing the Write
 * exception type because the 3G `GlobalExceptionHandler`
 * already maps it uniformly to 403 `auth.forbidden`. The
 * [com.medcore.platform.write.WriteDenialReason] on the throw
 * drives the read-denial audit row (see [ReadAuditor.onDenied]).
 *
 * Context parameter is [WriteContext] — deliberately REUSED
 * for reads. The name `WriteContext` is a legacy artefact of
 * 3J.1 (when writes were the only operation); the type's
 * shape `(principal, idempotencyKey)` is operation-agnostic.
 * A cross-cutting rename is tracked separately from 4A.4.
 * `idempotencyKey` is unused for reads but the field stays
 * nullable so shape parity with writes holds.
 */
fun interface ReadAuthzPolicy<CMD> {
    fun check(command: CMD, context: WriteContext)
}
