import { apiFetch } from '@/lib/api-client'

export type ProblemSeverity = 'MILD' | 'MODERATE' | 'SEVERE'

export type ProblemStatus =
  | 'ACTIVE'
  | 'INACTIVE'
  | 'RESOLVED'
  | 'ENTERED_IN_ERROR'

/**
 * Wire shape of `ProblemResponse` from
 * `POST .../patients/{id}/problems`,
 * `PATCH .../patients/{id}/problems/{id}`, and
 * `GET .../patients/{id}/problems` (Phase 4E.2).
 *
 * Condition text is PHI when combined with the patient FK —
 * never log, never persist client-side. The Problems card
 * + Manage modal are the only intended display surfaces.
 *
 * `severity` is **optional** (locked Q3 — many problems have
 * no clinically meaningful severity). When absent on the wire,
 * the property is omitted by `@JsonInclude(NON_NULL)` rather
 * than serialised as `null`. We type it `?` accordingly.
 *
 * `codeValue` / `codeSystem` are reserved for 5A FHIR + 3M
 * coded-concept attachment; always absent in 4E.2.
 *
 * `RESOLVED ≠ INACTIVE` — see ProblemStatus state machine doc
 * in the backend `ProblemStatus.kt` KDoc. Treat as semantically
 * distinct in every UI and analytics surface.
 */
export interface Problem {
  id: string
  tenantId: string
  patientId: string
  conditionText: string
  codeValue?: string
  codeSystem?: string
  severity?: ProblemSeverity
  status: ProblemStatus
  onsetDate?: string
  abatementDate?: string
  recordedInEncounterId?: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  rowVersion: number
}

export interface ProblemList {
  items: Problem[]
  pageInfo: import('./pagination').PageInfo
}

export interface ListPatientProblemsParams {
  tenantSlug: string
  patientId: string
  signal?: AbortSignal
}

export async function listPatientProblems(
  params: ListPatientProblemsParams,
): Promise<ProblemList> {
  const { tenantSlug, patientId, signal } = params
  return apiFetch<ProblemList>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/problems`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface AddProblemParams {
  tenantSlug: string
  patientId: string
  conditionText: string
  /**
   * NULLABLE per the wire contract. Omit when the clinician
   * has no clinically meaningful severity to record (the
   * common case for many problems).
   */
  severity?: ProblemSeverity
  onsetDate?: string
  abatementDate?: string
  recordedInEncounterId?: string
  signal?: AbortSignal
}

/**
 * Record a new problem on a patient (Phase 4E.2). Status is
 * always ACTIVE on insert; lifecycle transitions go through
 * [updateProblem].
 *
 * - 201 → returns the newly-created [Problem].
 * - 403 → caller lacks `PROBLEM_WRITE`.
 * - 404 → unknown / cross-tenant patientId (no leak).
 * - 422 → blank conditionText, unknown severity token,
 *   `abatementDate < onsetDate`, etc.
 */
export async function addProblem(params: AddProblemParams): Promise<Problem> {
  const {
    tenantSlug,
    patientId,
    conditionText,
    severity,
    onsetDate,
    abatementDate,
    recordedInEncounterId,
    signal,
  } = params
  return apiFetch<Problem>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/problems`,
    {
      method: 'POST',
      body: {
        conditionText,
        ...(severity !== undefined ? { severity } : {}),
        ...(onsetDate !== undefined ? { onsetDate } : {}),
        ...(abatementDate !== undefined ? { abatementDate } : {}),
        ...(recordedInEncounterId !== undefined
          ? { recordedInEncounterId }
          : {}),
      },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface UpdateProblemParams {
  tenantSlug: string
  patientId: string
  problemId: string
  /**
   * Caller's last-known `rowVersion`. Sent as `If-Match: "<n>"`.
   * Mismatch surfaces as `409 details.reason: stale_row` so the
   * UI can refetch + retry.
   */
  expectedRowVersion: number
  /**
   * Three-state per field — `undefined` means "do not touch
   * this column"; `null` means "set this column to NULL";
   * a value means "set this column to the given value."
   *
   * `severity` IS nullable (locked Q3) — `null` is legal and
   * clears the column. `status` is non-null and cannot be
   * cleared (validator returns 422). `onsetDate` /
   * `abatementDate` are nullable.
   */
  severity?: ProblemSeverity | null
  onsetDate?: string | null
  abatementDate?: string | null
  status?: ProblemStatus
  signal?: AbortSignal
}

/**
 * Patch a problem (Phase 4E.2). Mutable fields: severity,
 * onsetDate, abatementDate, status. `conditionText` is
 * immutable post-create — to "change" a condition, mark the
 * row ENTERED_IN_ERROR via this endpoint and call
 * [addProblem] with the corrected condition.
 *
 * ### Status transitions
 *
 *   - `ACTIVE ↔ INACTIVE` — bidirectional clinical refinement;
 *     emits `CLINICAL_PROBLEM_UPDATED` with `status_from/to`.
 *   - `ACTIVE → RESOLVED` / `INACTIVE → RESOLVED` — clinical-
 *     outcome cure path; emits `CLINICAL_PROBLEM_RESOLVED`
 *     with `prior_status`.
 *   - `RESOLVED → ACTIVE` — recurrence; emits
 *     `CLINICAL_PROBLEM_UPDATED` with
 *     `status_from:RESOLVED|status_to:ACTIVE`.
 *   - `RESOLVED → INACTIVE` — REFUSED (RESOLVED ≠ INACTIVE)
 *     → 409 `details.reason: problem_invalid_transition`.
 *   - any → `ENTERED_IN_ERROR` — terminal retraction; emits
 *     `CLINICAL_PROBLEM_REVOKED` with `prior_status`.
 *   - `ENTERED_IN_ERROR → anything` (with actual change) →
 *     409 `details.reason: problem_terminal`.
 *
 * ### Errors
 *
 *   - 200 → returns the patched [Problem] (post-state).
 *   - 403 → caller lacks `PROBLEM_WRITE`.
 *   - 404 → unknown problemId / cross-tenant / cross-patient.
 *   - 409 `details.reason: stale_row` — If-Match mismatch.
 *   - 409 `details.reason: problem_terminal` — terminal-state
 *     attempted-mutation refusal.
 *   - 409 `details.reason: problem_invalid_transition` —
 *     RESOLVED → INACTIVE explicit refusal (the load-bearing
 *     RESOLVED ≠ INACTIVE invariant; do NOT try to bypass
 *     it client-side — go through ACTIVE first or
 *     ENTERED_IN_ERROR if the resolution itself was wrong).
 *   - 422 → unknown enum, malformed date, etc.
 *   - 428 → caller forgot to send `expectedRowVersion`.
 *     Should never happen via this lib.
 */
export async function updateProblem(
  params: UpdateProblemParams,
): Promise<Problem> {
  const {
    tenantSlug,
    patientId,
    problemId,
    expectedRowVersion,
    severity,
    onsetDate,
    abatementDate,
    status,
    signal,
  } = params
  // Build the JSON body honoring three-state semantics:
  //   - field absent  → property NOT set on body
  //   - field null    → property SET to null (Clear)
  //   - field value   → property SET to value (Set)
  const body: Record<string, unknown> = {}
  if (severity !== undefined) body.severity = severity
  if (onsetDate !== undefined) body.onsetDate = onsetDate
  if (abatementDate !== undefined) body.abatementDate = abatementDate
  if (status !== undefined) body.status = status

  return apiFetch<Problem>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/problems/${encodeURIComponent(problemId)}`,
    {
      method: 'PATCH',
      body,
      tenantSlug,
      headers: { 'If-Match': `"${expectedRowVersion}"` },
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
