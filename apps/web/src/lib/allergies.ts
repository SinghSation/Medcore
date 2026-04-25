import { apiFetch } from '@/lib/api-client'

export type AllergySeverity =
  | 'MILD'
  | 'MODERATE'
  | 'SEVERE'
  | 'LIFE_THREATENING'

export type AllergyStatus = 'ACTIVE' | 'INACTIVE' | 'ENTERED_IN_ERROR'

/**
 * Wire shape of `AllergyResponse` from
 * `POST .../patients/{id}/allergies`,
 * `PATCH .../patients/{id}/allergies/{id}`, and
 * `GET .../patients/{id}/allergies` (Phase 4E.1).
 *
 * Substance + reaction text are PHI when combined with the
 * patient FK — never log, never persist client-side. The
 * banner UI rendering is the only intended display surface.
 *
 * `substanceCode` / `substanceSystem` are reserved for 5A
 * FHIR coding; always absent in 4E.1 (`@JsonInclude(NON_NULL)`
 * strips them when null).
 */
export interface Allergy {
  id: string
  tenantId: string
  patientId: string
  substanceText: string
  substanceCode?: string
  substanceSystem?: string
  severity: AllergySeverity
  status: AllergyStatus
  reactionText?: string
  onsetDate?: string
  recordedInEncounterId?: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  rowVersion: number
}

export interface AllergyList {
  items: Allergy[]
  pageInfo: import('./pagination').PageInfo
}

export interface ListPatientAllergiesParams {
  tenantSlug: string
  patientId: string
  signal?: AbortSignal
}

export async function listPatientAllergies(
  params: ListPatientAllergiesParams,
): Promise<AllergyList> {
  const { tenantSlug, patientId, signal } = params
  return apiFetch<AllergyList>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/allergies`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface AddAllergyParams {
  tenantSlug: string
  patientId: string
  substanceText: string
  severity: AllergySeverity
  reactionText?: string
  onsetDate?: string
  recordedInEncounterId?: string
  signal?: AbortSignal
}

/**
 * Record a new allergy on a patient (Phase 4E.1). Status is
 * always ACTIVE on insert; lifecycle transitions go through
 * [updateAllergy].
 *
 * - 201 → returns the newly-created [Allergy].
 * - 403 → caller lacks `ALLERGY_WRITE`.
 * - 404 → unknown / cross-tenant patientId (no leak).
 * - 422 → blank substance, unknown severity token, body too
 *   long, etc.
 */
export async function addAllergy(params: AddAllergyParams): Promise<Allergy> {
  const {
    tenantSlug,
    patientId,
    substanceText,
    severity,
    reactionText,
    onsetDate,
    recordedInEncounterId,
    signal,
  } = params
  return apiFetch<Allergy>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/allergies`,
    {
      method: 'POST',
      body: {
        substanceText,
        severity,
        ...(reactionText !== undefined ? { reactionText } : {}),
        ...(onsetDate !== undefined ? { onsetDate } : {}),
        ...(recordedInEncounterId !== undefined
          ? { recordedInEncounterId }
          : {}),
      },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface UpdateAllergyParams {
  tenantSlug: string
  patientId: string
  allergyId: string
  /**
   * Caller's last-known `rowVersion`. Sent as `If-Match: "<n>"`.
   * Mismatch surfaces as `409 details.reason: stale_row` so the
   * UI can refetch + retry.
   */
  expectedRowVersion: number
  /**
   * Three-state per field — undefined means "do not touch this
   * column"; null means "set this column to NULL"; a value means
   * "set this column to the given value." Mirrors the backend
   * Patchable<T> sealed hierarchy.
   *
   * `severity` and `status` cannot be cleared (the backend
   * rejects null on those). `reactionText` and `onsetDate` are
   * nullable.
   */
  severity?: AllergySeverity
  reactionText?: string | null
  onsetDate?: string | null
  status?: AllergyStatus
  signal?: AbortSignal
}

/**
 * Patch an allergy (Phase 4E.1). Mutable fields: severity,
 * reactionText, onsetDate, status. Substance is immutable
 * post-create — to "change" a substance, mark the row
 * ENTERED_IN_ERROR via this endpoint and call [addAllergy]
 * with the corrected substance.
 *
 * Status transitions:
 *   - `ACTIVE ↔ INACTIVE` — bidirectional clinical refinement.
 *   - any → `ENTERED_IN_ERROR` — terminal retraction (audited
 *     as `CLINICAL_ALLERGY_REVOKED`).
 *   - `ENTERED_IN_ERROR` → anything (with actual change) → 409
 *     `details.reason: allergy_terminal`.
 *
 * Errors:
 *   - 200 → returns the patched [Allergy] (post-state).
 *   - 403 → caller lacks `ALLERGY_WRITE`.
 *   - 404 → unknown allergyId / cross-tenant / cross-patient.
 *   - 409 `details.reason: stale_row` — If-Match mismatch.
 *   - 409 `details.reason: allergy_terminal` — terminal-state
 *     attempted-mutation refusal.
 *   - 422 → unknown enum, malformed date, etc.
 *   - 428 → caller forgot to send `expectedRowVersion`. Should
 *     never happen via this lib.
 */
export async function updateAllergy(
  params: UpdateAllergyParams,
): Promise<Allergy> {
  const {
    tenantSlug,
    patientId,
    allergyId,
    expectedRowVersion,
    severity,
    reactionText,
    onsetDate,
    status,
    signal,
  } = params
  // Build the JSON body honoring three-state semantics:
  //   - field absent  → property NOT set on body
  //   - field null    → property SET to null (Clear)
  //   - field value   → property SET to value (Set)
  const body: Record<string, unknown> = {}
  if (severity !== undefined) body.severity = severity
  if (reactionText !== undefined) body.reactionText = reactionText
  if (onsetDate !== undefined) body.onsetDate = onsetDate
  if (status !== undefined) body.status = status

  return apiFetch<Allergy>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/allergies/${encodeURIComponent(allergyId)}`,
    {
      method: 'PATCH',
      body,
      tenantSlug,
      headers: { 'If-Match': `"${expectedRowVersion}"` },
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
