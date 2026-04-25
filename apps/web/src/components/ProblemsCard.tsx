import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiError } from '@/lib/api-client'
import {
  addProblem,
  listPatientProblems,
  updateProblem,
  type Problem,
  type ProblemSeverity,
  type ProblemStatus,
} from '@/lib/problems'

const SEVERITY_OPTIONS: ReadonlyArray<{
  value: ProblemSeverity | ''
  label: string
}> = [
  // Severity is NULLABLE per locked Q3 — the empty option is the
  // "Unspecified" choice and serialises to severity omitted from
  // the request body (NOT severity:null). Add form default.
  { value: '', label: 'Unspecified' },
  { value: 'MILD', label: 'Mild' },
  { value: 'MODERATE', label: 'Moderate' },
  { value: 'SEVERE', label: 'Severe' },
]

function severityLabel(value: ProblemSeverity | undefined): string {
  if (value === undefined) return ''
  return SEVERITY_OPTIONS.find((o) => o.value === value)?.label ?? value
}

function statusLabel(status: ProblemStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'Active'
    case 'INACTIVE':
      return 'Inactive'
    case 'RESOLVED':
      // RESOLVED ≠ INACTIVE — labels are deliberately distinct
      // so a clinician scanning the management modal cannot
      // mistake one for the other.
      return 'Resolved'
    case 'ENTERED_IN_ERROR':
      return 'Entered in error'
  }
}

/**
 * Phase 4E.2 problem list — chart-context surface mounted on
 * the patient detail page only (locked Q6 — distinct from
 * 4E.1 allergies which mount on both patient + encounter
 * pages because allergies are clinical-safety, problems are
 * chart context).
 *
 * The card shows ACTIVE problems only — clinicians can scan
 * the at-a-glance list for currently-active diagnoses without
 * being distracted by INACTIVE / RESOLVED entries. The
 * **Manage** modal opens the full list (every status,
 * including the data-quality ENTERED_IN_ERROR rows) for
 * lifecycle transitions.
 *
 * Write controls are always visible (Manage button + add
 * form / status-transition buttons inside the modal). The
 * backend enforces PROBLEM_WRITE — a MEMBER who clicks Add
 * gets a 403 and the inline error banner. Mirrors the 4E.1
 * frontend posture (UI doesn't gate on role; API is the
 * source of truth).
 */
export function ProblemsCard({
  tenantSlug,
  patientId,
}: {
  tenantSlug: string
  patientId: string
}): React.JSX.Element {
  const queryClient = useQueryClient()
  const problemsQuery = useQuery({
    queryKey: ['problems', tenantSlug, patientId],
    queryFn: ({ signal }) =>
      listPatientProblems({ tenantSlug, patientId, signal }),
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  const [manageOpen, setManageOpen] = useState(false)

  // Card shows ACTIVE only — chart-context filter. INACTIVE +
  // RESOLVED + ENTERED_IN_ERROR live inside the management
  // modal. RESOLVED ≠ INACTIVE — they're separate buckets.
  const activeProblems = useMemo<Problem[]>(
    () =>
      problemsQuery.data?.items.filter((p) => p.status === 'ACTIVE') ?? [],
    [problemsQuery.data?.items],
  )

  const allItems = problemsQuery.data?.items ?? []
  const isLoading = problemsQuery.isLoading

  return (
    <Card data-phi data-testid="problems-card">
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">Problems</CardTitle>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setManageOpen(true)}
          data-testid="problem-manage-button"
        >
          Manage
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p
            className="text-muted-foreground text-sm"
            data-testid="problems-card-loading"
          >
            Loading problems…
          </p>
        )}
        {!isLoading && problemsQuery.isError && (
          <p
            className="text-destructive text-sm"
            data-testid="problems-card-error"
          >
            Could not load problems.
          </p>
        )}
        {!isLoading && !problemsQuery.isError && activeProblems.length === 0 && (
          <p
            className="text-muted-foreground text-sm"
            data-testid="problems-card-empty"
          >
            No active problems recorded.
          </p>
        )}
        {!isLoading && activeProblems.length > 0 && (
          <ul
            className="flex flex-col gap-2"
            data-testid="problems-card-list"
          >
            {activeProblems.map((p) => (
              <li
                key={p.id}
                className="flex items-center justify-between rounded-md border p-3"
                data-testid="problem-card-row"
                data-problem-status={p.status}
              >
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    {p.severity !== undefined && (
                      <span
                        className="bg-muted rounded-full px-2 py-0.5 text-xs font-semibold uppercase"
                        data-testid="problem-card-severity"
                      >
                        {severityLabel(p.severity)}
                      </span>
                    )}
                    <span className="text-sm font-medium">
                      {p.conditionText}
                    </span>
                  </div>
                  {(p.onsetDate || p.abatementDate) && (
                    <p className="text-muted-foreground text-xs">
                      {p.onsetDate && `Onset ${p.onsetDate}`}
                      {p.onsetDate && p.abatementDate && ' · '}
                      {p.abatementDate && `Abated ${p.abatementDate}`}
                    </p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      {manageOpen && (
        <ProblemManageModal
          tenantSlug={tenantSlug}
          patientId={patientId}
          items={allItems}
          onClose={() => setManageOpen(false)}
          onMutated={() =>
            queryClient.invalidateQueries({
              queryKey: ['problems', tenantSlug, patientId],
            })
          }
        />
      )}
    </Card>
  )
}

// ============================================================================
// Manage modal — full list (all statuses) + add + transition controls
// ============================================================================

function ProblemManageModal({
  tenantSlug,
  patientId,
  items,
  onClose,
  onMutated,
}: {
  tenantSlug: string
  patientId: string
  items: Problem[]
  onClose: () => void
  onMutated: () => void
}): React.JSX.Element {
  const [conditionText, setConditionText] = useState('')
  // Severity defaults to '' (unspecified) — locked Q3 nullable
  // semantic. Submitting with '' omits severity from the request.
  const [severity, setSeverity] = useState<ProblemSeverity | ''>('')
  const [addError, setAddError] = useState<string | null>(null)

  const addMutation = useMutation({
    mutationFn: () =>
      addProblem({
        tenantSlug,
        patientId,
        conditionText: conditionText.trim(),
        ...(severity !== '' ? { severity } : {}),
      }),
    onSuccess: () => {
      setConditionText('')
      setSeverity('')
      setAddError(null)
      onMutated()
    },
    onError: (err) => {
      setAddError(addErrorMessage(err))
    },
  })

  const [transitionError, setTransitionError] = useState<string | null>(null)
  const [pendingProblemId, setPendingProblemId] = useState<string | null>(null)
  const transitionMutation = useMutation({
    mutationFn: (vars: {
      problemId: string
      expectedRowVersion: number
      status: ProblemStatus
    }) =>
      updateProblem({
        tenantSlug,
        patientId,
        problemId: vars.problemId,
        expectedRowVersion: vars.expectedRowVersion,
        status: vars.status,
      }),
    onSuccess: () => {
      setPendingProblemId(null)
      setTransitionError(null)
      onMutated()
    },
    onError: (err) => {
      setPendingProblemId(null)
      setTransitionError(transitionErrorMessage(err))
    },
  })

  function onTransition(p: Problem, status: ProblemStatus): void {
    setTransitionError(null)
    setPendingProblemId(p.id)
    transitionMutation.mutate({
      problemId: p.id,
      expectedRowVersion: p.rowVersion,
      status,
    })
  }

  function onAdd(): void {
    if (conditionText.trim().length === 0) return
    setAddError(null)
    addMutation.mutate()
  }

  // Sort by status priority for the management view: ACTIVE
  // first, then INACTIVE, then RESOLVED, then ENTERED_IN_ERROR
  // (newest first within each bucket). RESOLVED gets its own
  // slot — distinct from INACTIVE — preserving the load-bearing
  // RESOLVED ≠ INACTIVE distinction in the rendered order.
  const sortedItems = useMemo<Problem[]>(() => {
    const priority: Record<ProblemStatus, number> = {
      ACTIVE: 0,
      INACTIVE: 1,
      RESOLVED: 2,
      ENTERED_IN_ERROR: 3,
    }
    return [...items].sort(
      (a, b) =>
        priority[a.status] - priority[b.status] ||
        a.createdAt.localeCompare(b.createdAt) * -1,
    )
  }, [items])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="problem-manage-title"
      tabIndex={-1}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
      onClick={(e) => {
        // Backdrop click — only dismiss when the click is on
        // the overlay itself, not on the dialog content. Same
        // pattern as AllergyBanner; focus trap deferred to an
        // app-wide a11y slice.
        if (e.target === e.currentTarget) onClose()
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
      data-testid="problem-manage-modal"
    >
      <div
        className="bg-background w-full max-w-2xl rounded-lg border p-6 shadow-lg"
        data-phi
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 id="problem-manage-title" className="text-lg font-semibold">
            Manage problems
          </h2>
          <Button
            variant="outline"
            size="sm"
            onClick={onClose}
            data-testid="problem-manage-close"
          >
            Close
          </Button>
        </div>

        {/* Add form */}
        <div className="mb-6 flex flex-col gap-2 rounded-md border p-3">
          <label
            htmlFor="problem-condition"
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Condition
          </label>
          <input
            id="problem-condition"
            type="text"
            value={conditionText}
            onChange={(e) => setConditionText(e.target.value)}
            disabled={addMutation.isPending}
            maxLength={500}
            placeholder="e.g., Type 2 diabetes mellitus"
            data-testid="problem-condition-input"
            className="border-input rounded-md border bg-transparent px-3 py-2 text-sm"
          />
          <label
            htmlFor="problem-severity"
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Severity (optional)
          </label>
          <select
            id="problem-severity"
            value={severity}
            onChange={(e) =>
              setSeverity(e.target.value as ProblemSeverity | '')
            }
            disabled={addMutation.isPending}
            data-testid="problem-severity-select"
            className="border-input rounded-md border bg-transparent px-3 py-2 text-sm"
          >
            {SEVERITY_OPTIONS.map((o) => (
              <option key={o.value || 'unspecified'} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          {addError !== null && (
            <p
              role="alert"
              className="text-destructive text-xs"
              data-testid="problem-add-error"
            >
              {addError}
            </p>
          )}
          <div className="flex justify-end">
            <Button
              size="sm"
              onClick={onAdd}
              disabled={
                addMutation.isPending || conditionText.trim().length === 0
              }
              data-testid="problem-add-button"
            >
              {addMutation.isPending ? 'Adding…' : 'Add problem'}
            </Button>
          </div>
        </div>

        {/* Existing problems — full list (all statuses including
            RESOLVED + ENTERED_IN_ERROR) */}
        {sortedItems.length === 0 ? (
          <p
            className="text-muted-foreground text-sm"
            data-testid="problem-manage-empty"
          >
            No problems on file yet.
          </p>
        ) : (
          <ul
            className="flex flex-col gap-2"
            data-testid="problem-manage-list"
          >
            {sortedItems.map((p) => (
              <li
                key={p.id}
                className={
                  p.status === 'ACTIVE'
                    ? 'rounded-md border p-3'
                    : 'rounded-md border bg-muted/40 p-3 opacity-70'
                }
                data-testid="problem-manage-row"
                data-problem-id={p.id}
                data-problem-status={p.status}
              >
                <div className="mb-1 flex items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium">
                      {p.conditionText}
                    </span>
                    {p.severity !== undefined && (
                      <span className="bg-muted rounded-full px-2 py-0.5 text-xs font-semibold">
                        {severityLabel(p.severity)}
                      </span>
                    )}
                    <span
                      className={statusBadgeClassName(p.status)}
                      data-testid="problem-manage-status"
                    >
                      {statusLabel(p.status)}
                    </span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {p.status === 'ACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(p, 'INACTIVE')}
                        disabled={pendingProblemId === p.id}
                        data-testid="problem-deactivate-button"
                      >
                        Mark inactive
                      </Button>
                    )}
                    {p.status === 'INACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(p, 'ACTIVE')}
                        disabled={pendingProblemId === p.id}
                        data-testid="problem-reactivate-button"
                      >
                        Reactivate
                      </Button>
                    )}
                    {/* Resolve — only from ACTIVE / INACTIVE.
                        RESOLVED is a clinical-outcome action with
                        its own audit (CLINICAL_PROBLEM_RESOLVED). */}
                    {(p.status === 'ACTIVE' || p.status === 'INACTIVE') && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(p, 'RESOLVED')}
                        disabled={pendingProblemId === p.id}
                        data-testid="problem-resolve-button"
                      >
                        Mark resolved
                      </Button>
                    )}
                    {/* Reactivate from RESOLVED = recurrence
                        (status_from:RESOLVED|status_to:ACTIVE).
                        Routes to UPDATED audit, NOT a new RESOLVED. */}
                    {p.status === 'RESOLVED' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(p, 'ACTIVE')}
                        disabled={pendingProblemId === p.id}
                        data-testid="problem-recurrence-button"
                      >
                        Mark active (recurrence)
                      </Button>
                    )}
                    {p.status !== 'ENTERED_IN_ERROR' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(p, 'ENTERED_IN_ERROR')}
                        disabled={pendingProblemId === p.id}
                        data-testid="problem-revoke-button"
                      >
                        Mark entered in error
                      </Button>
                    )}
                  </div>
                </div>
                {(p.onsetDate || p.abatementDate) && (
                  <p className="text-muted-foreground text-xs">
                    {p.onsetDate && `Onset ${p.onsetDate}`}
                    {p.onsetDate && p.abatementDate && ' · '}
                    {p.abatementDate && `Abated ${p.abatementDate}`}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
        {transitionError !== null && (
          <p
            role="alert"
            className="text-destructive mt-3 text-xs"
            data-testid="problem-transition-error"
          >
            {transitionError}
          </p>
        )}
      </div>
    </div>
  )
}

function statusBadgeClassName(status: ProblemStatus): string {
  // Distinct visual treatment per status — RESOLVED gets its
  // own muted-success styling so it cannot visually collide
  // with INACTIVE (load-bearing RESOLVED ≠ INACTIVE).
  switch (status) {
    case 'ACTIVE':
      return 'bg-primary/15 text-primary rounded-full px-2 py-0.5 text-xs font-semibold'
    case 'INACTIVE':
      return 'bg-secondary text-secondary-foreground rounded-full px-2 py-0.5 text-xs font-semibold'
    case 'RESOLVED':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400 rounded-full px-2 py-0.5 text-xs font-semibold'
    case 'ENTERED_IN_ERROR':
      return 'bg-destructive/15 text-destructive rounded-full px-2 py-0.5 text-xs font-semibold'
  }
}

function addErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return 'You do not have authority to add problems on this tenant.'
    }
    if (err.status === 422) {
      return 'Invalid problem. Check condition text and dates, then try again.'
    }
  }
  return 'Could not add problem. Try again.'
}

function transitionErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return 'You do not have authority to update problems on this tenant.'
    }
    if (err.status === 409) {
      const reason = extractConflictReason(err)
      // Three deterministic conflict reasons map to user-visible
      // copy. RESOLVED ≠ INACTIVE is surfaced explicitly so the
      // user understands WHY the transition was refused (and
      // what the legal alternatives are).
      if (reason === 'problem_terminal') {
        return 'This problem was already retracted. Refresh to see the current state.'
      }
      if (reason === 'problem_invalid_transition') {
        return (
          'A resolved problem cannot go directly to inactive. ' +
          'Mark it active again (recurrence) or, if the resolution itself was wrong, ' +
          'mark it entered in error.'
        )
      }
      if (reason === 'stale_row') {
        return 'This problem was changed by someone else. Refresh and try again.'
      }
      return 'This problem state changed. Refresh and try again.'
    }
  }
  return 'Could not update problem. Try again.'
}

function extractConflictReason(err: ApiError): string | null {
  if (typeof err.body !== 'object' || err.body === null) return null
  const details = (err.body as { details?: unknown }).details
  if (typeof details !== 'object' || details === null) return null
  const reason = (details as { reason?: unknown }).reason
  return typeof reason === 'string' ? reason : null
}
