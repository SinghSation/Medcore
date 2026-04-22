package com.medcore.tenancy.write

/**
 * Command for the `PATCH /api/v1/tenants/{slug}` tenancy write
 * (Phase 3J.2, ADR-007 §2.4).
 *
 * Crossing the controller → [com.medcore.platform.write.WriteGate]
 * boundary with a typed command object (not a DTO, not a map) is
 * deliberate: the policy / validator / handler / auditor all
 * operate on the same narrow contract, and the intent-slug the
 * auditor emits (`intent:tenant.update_display_name`) is derived
 * from the command's semantic identity, not the HTTP verb.
 *
 * [displayName] is the AUTHORITATIVE value the tenant row should
 * carry after the write. Trimming, length-bounding, and control-
 * character screening live in
 * [UpdateTenantDisplayNameValidator]; by the time [displayName]
 * reaches the handler, it is already normalised.
 */
data class UpdateTenantDisplayNameCommand(
    val slug: String,
    val displayName: String,
)
