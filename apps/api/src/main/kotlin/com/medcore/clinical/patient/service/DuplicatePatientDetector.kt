package com.medcore.clinical.patient.service

import com.medcore.platform.security.phi.PhiSessionContext
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Max candidates returned to the caller. Ten is enough to surface
 * likely matches without turning the response into a bulk-export
 * surface.
 *
 * Declared file-top (not inside a `companion object`) so the
 * generated `DuplicatePatientDetector$Companion` nested class does
 * not exist — ArchUnit Rule 13's package filter includes ALL
 * classes in `..clinical..service..`, and a companion class without
 * its own [PhiSessionContext] dependency would fail the rule even
 * though the outer class satisfies it.
 */
private const val MAX_CANDIDATES: Int = 10

private const val EXACT_MATCH_SQL: String = """
    SELECT id, mrn
      FROM clinical.patient
     WHERE tenant_id = ?
       AND birth_date = ?
       AND lower(name_family) = ?
       AND lower(name_given) = ?
       AND status = 'ACTIVE'
     LIMIT 10
"""

/**
 * Phonetic match — same DOB, soundex-equal family name, via the
 * fuzzystrmatch extension installed in V14 and moved to the
 * `public` schema in V16 (Phase 4A.2). `public.soundex` MUST be
 * the qualified form — unqualified resolution depends on role-level
 * search_path, which Medcore does not rely on for correctness.
 */
private const val PHONETIC_MATCH_SQL: String = """
    SELECT id, mrn
      FROM clinical.patient
     WHERE tenant_id = ?
       AND birth_date = ?
       AND public.soundex(name_family) = public.soundex(?)
       AND status = 'ACTIVE'
     LIMIT 10
"""

/**
 * Surfaces candidate duplicate patients on create (Phase 4A.2).
 *
 * ### Why it lives in `..clinical.patient.service..`
 *
 * This is the first `..clinical..service..` class in Medcore.
 * Its location activates **ArchUnit Rule 13** (`ClinicalDisciplineArchTest`):
 * every class in `..clinical..service..` MUST depend on
 * [PhiSessionContext]. The dependency is genuine — the detector
 * calls [PhiSessionContext.applyFromRequest] as a defensive
 * assertion that the PhiRequestContext holder is populated
 * BEFORE it issues PHI-touching SELECTs.
 *
 * In the 4A.2 happy path, the detector runs inside the
 * `WriteGate` transaction where `PhiRlsTxHook` has already set
 * both RLS GUCs — the call to `applyFromRequest()` is
 * idempotent (sets the GUCs to the same values). If a future
 * caller ever wires the detector outside a gated path (a
 * scheduled consistency check, an async job), the call throws
 * `PhiContextMissingException` → 500 `server.error`. **Loud
 * failure beats a silent zero-row read on PHI tables.**
 *
 * ### Detection strategy (Phase 4A.2)
 *
 * Two queries hit V14's duplicate-detection indexes:
 *
 * 1. **Exact match** on `(tenant_id, birth_date, lower(name_family),
 *    lower(name_given))` — hits `ix_clinical_patient_tenant_dob_family_given`.
 * 2. **Phonetic match** on `(tenant_id, birth_date, soundex(name_family))`
 *    — hits `ix_clinical_patient_tenant_soundex_family`.
 *
 * Results from both queries are merged (distinct by patient id),
 * capped at [MAX_CANDIDATES].
 *
 * ### Minimal candidate disclosure
 *
 * Candidates are returned as [DuplicateCandidate] carrying only
 * `patientId` + `mrn`. Name parts, DOB, and demographics are
 * deliberately NOT echoed — avoids turning the create endpoint
 * into a PHI-search oracle. Callers holding `PATIENT_CREATE`
 * (OWNER/ADMIN) can resolve names via a future
 * `GET /patients/{id}` endpoint (4A.5), which has its own
 * read-audit envelope.
 *
 * ### Rate-limiting future work (design-pack refinement #2)
 *
 * The duplicate-warning endpoint is a candidate for enumeration
 * abuse: a hostile admin can submit synthetic patient payloads
 * and read back candidate UUIDs + MRNs to enumerate the tenant's
 * patient list. 4A.2 does NOT rate-limit this path — the
 * authority gate (PATIENT_CREATE, OWNER/ADMIN only) is the
 * primary mitigation; rate-limiting would close the residual
 * gap. Tracked as a carry-forward: see
 * `docs/product/02-roadmap.md` (carry-forward ledger) and
 * `docs/security/phi-exposure-review-4a-2.md §5`.
 */
@Component
class DuplicatePatientDetector(
    private val jdbcTemplate: JdbcTemplate,
    private val phiSessionContext: PhiSessionContext,
) {

    fun detect(
        tenantId: UUID,
        birthDate: LocalDate,
        nameFamily: String,
        nameGiven: String,
    ): List<DuplicateCandidate> {
        // Defensive reinforcement of the PHI context. In the 4A.2
        // create flow the GUCs are already set by PhiRlsTxHook;
        // this call throws only if the PhiRequestContext holder is
        // empty (i.e., the detector has been reached outside the
        // HTTP filter chain without manual context establishment).
        // That's a bug the caller must fix, not a user-facing error.
        phiSessionContext.applyFromRequest()

        val exact = jdbcTemplate.query(
            EXACT_MATCH_SQL,
            { rs, _ ->
                DuplicateCandidate(
                    patientId = UUID.fromString(rs.getString("id")),
                    mrn = rs.getString("mrn"),
                )
            },
            tenantId,
            birthDate,
            nameFamily.lowercase(),
            nameGiven.lowercase(),
        )
        val phonetic = jdbcTemplate.query(
            PHONETIC_MATCH_SQL,
            { rs, _ ->
                DuplicateCandidate(
                    patientId = UUID.fromString(rs.getString("id")),
                    mrn = rs.getString("mrn"),
                )
            },
            tenantId,
            birthDate,
            nameFamily,
        )

        // Merge + distinct-by-id + cap. LinkedHashSet preserves
        // "exact match first" ordering — callers picking the first
        // candidate get the strongest match.
        val merged = LinkedHashSet<DuplicateCandidate>(MAX_CANDIDATES * 2)
        merged.addAll(exact)
        merged.addAll(phonetic)
        return merged.take(MAX_CANDIDATES)
    }
}

/**
 * Minimal-disclosure candidate record surfaced on duplicate
 * warnings. Carries only stable identifiers — never name, DOB,
 * or demographic fields. See [DuplicatePatientDetector] KDoc
 * for rationale.
 */
data class DuplicateCandidate(
    val patientId: UUID,
    val mrn: String,
)
