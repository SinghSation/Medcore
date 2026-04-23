package com.medcore.clinical.patient.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [AddPatientIdentifierCommand] (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §4:
 * `[REQUIRED §4.1]` `@Component` implementing `AuthzPolicy<CMD>`;
 * runs OUTSIDE the transaction.
 *
 * ### Authority reuse (per 4A.3 master plan §4.2)
 *
 * Reuses [MedcoreAuthority.PATIENT_UPDATE] rather than introducing
 * a dedicated `PATIENT_IDENTIFIER_CREATE`. Rationale: identifier
 * management is semantically a form of patient-record update. The
 * 4A.1 role map already grants `PATIENT_UPDATE` to OWNER + ADMIN
 * and withholds it from MEMBER. Introducing a new authority
 * without a concrete clinical-role differentiation consumer would
 * be premature granularity.
 *
 * Future slice (clinical-role differentiation) that adds roles
 * like CLINICIAN / NURSE / STAFF may split identifier management
 * out into its own authority via a dedicated ADR.
 *
 * ### Defense-in-depth
 *
 * V17 (4A.3 Chunk A) tightens the RLS write policies on
 * `clinical.patient_identifier` to require OWNER/ADMIN at the DB
 * layer too. Belt-and-braces: this policy refuses MEMBER callers
 * at the application layer; V17 refuses them at the persistence
 * layer. Either gate is sufficient alone; both together are the
 * defence-in-depth contract the clinical-write-pattern v1.0 §4
 * prescribes for PHI writes.
 */
@Component
class AddPatientIdentifierPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<AddPatientIdentifierCommand> {

    override fun check(command: AddPatientIdentifierCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(
            userId = context.principal.userId,
            tenantSlug = command.slug,
        )
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }
        if (MedcoreAuthority.PATIENT_UPDATE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
