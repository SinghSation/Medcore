package com.medcore.platform.write

import com.medcore.platform.security.MedcorePrincipal

/**
 * Execution context passed to every [WriteGate.apply] call (Phase 3J).
 *
 * The context carries the caller's identity AND forward-looking slots
 * for capabilities that will land in future slices (idempotency,
 * request metadata) without forcing signature churn on consumers when
 * those slots get real implementations.
 *
 * ### Fields
 *
 * - [principal] — authenticated caller. Required. All authorization
 *   decisions derive from it.
 * - [idempotencyKey] — client-supplied token that makes retrying the
 *   same mutation safe. **Shape-only in 3J — the framework does not
 *   yet de-duplicate on this key.** A future slice stores the key +
 *   response on first call and replays the stored response on
 *   repeats. Every future mutation caller (payments, clinical
 *   orders) will rely on this; baking it into the signature now
 *   means those callers don't drive a post-hoc refactor.
 */
data class WriteContext(
    val principal: MedcorePrincipal,
    val idempotencyKey: String? = null,
)
