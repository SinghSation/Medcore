import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  addAllergy,
  listPatientAllergies,
  updateAllergy,
  type Allergy,
  type AllergySeverity,
  type AllergyStatus,
} from '@/lib/allergies'
import { ApiError } from '@/lib/api-client'

const SEVERITY_OPTIONS: ReadonlyArray<{
  value: AllergySeverity
  label: string
}> = [
  { value: 'MILD', label: 'Mild' },
  { value: 'MODERATE', label: 'Moderate' },
  { value: 'SEVERE', label: 'Severe' },
  { value: 'LIFE_THREATENING', label: 'Life-threatening' },
]

function severityLabel(value: AllergySeverity): string {
  return SEVERITY_OPTIONS.find((o) => o.value === value)?.label ?? value
}

/**
 * Phase 4E.1 allergy banner — clinical-safety surface mounted on
 * both the patient detail and encounter detail pages. Shows
 * ACTIVE allergies prominently; INACTIVE / ENTERED_IN_ERROR rows
 * surface only inside the management view (Manage button).
 *
 * The banner is rendered for every viewer; write controls are
 * always visible. The backend enforces ALLERGY_WRITE — a MEMBER
 * who clicks Add will get a 403 and the inline error banner.
 * Mirrors the existing 4D.5 / 4D.6 frontend posture (UI doesn't
 * gate on role; API is the source of truth).
 */
export function AllergyBanner({
  tenantSlug,
  patientId,
}: {
  tenantSlug: string
  patientId: string
}): React.JSX.Element {
  const queryClient = useQueryClient()
  const allergiesQuery = useQuery({
    queryKey: ['allergies', tenantSlug, patientId],
    queryFn: ({ signal }) =>
      listPatientAllergies({ tenantSlug, patientId, signal }),
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  const [manageOpen, setManageOpen] = useState(false)

  // Banner shows ACTIVE only — clinical-safety filter. Memoized
  // so unrelated re-renders don't re-walk the list.
  const activeAllergies = useMemo<Allergy[]>(
    () =>
      allergiesQuery.data?.items.filter((a) => a.status === 'ACTIVE') ?? [],
    [allergiesQuery.data?.items],
  )

  const allItems = allergiesQuery.data?.items ?? []
  const isLoading = allergiesQuery.isLoading

  return (
    <Card data-phi data-testid="allergy-banner">
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">Allergies</CardTitle>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setManageOpen(true)}
          data-testid="allergy-manage-button"
        >
          Manage
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p
            className="text-muted-foreground text-sm"
            data-testid="allergy-banner-loading"
          >
            Loading allergies…
          </p>
        )}
        {!isLoading && allergiesQuery.isError && (
          <p
            className="text-destructive text-sm"
            data-testid="allergy-banner-error"
          >
            Could not load allergies.
          </p>
        )}
        {!isLoading && !allergiesQuery.isError && activeAllergies.length === 0 && (
          <p
            className="text-muted-foreground text-sm"
            data-testid="allergy-banner-empty"
          >
            No allergies recorded.
          </p>
        )}
        {!isLoading && activeAllergies.length > 0 && (
          <ul
            className="flex flex-col gap-2"
            data-testid="allergy-banner-list"
          >
            {activeAllergies.map((a) => (
              <li
                key={a.id}
                className="border-destructive/30 bg-destructive/5 flex items-center justify-between rounded-md border p-3"
                data-testid="allergy-banner-row"
                data-allergy-status={a.status}
              >
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    <span
                      className="bg-destructive/15 text-destructive rounded-full px-2 py-0.5 text-xs font-semibold uppercase"
                      data-testid="allergy-banner-severity"
                    >
                      {severityLabel(a.severity)}
                    </span>
                    <span className="text-sm font-medium">
                      {a.substanceText}
                    </span>
                  </div>
                  {a.reactionText && (
                    <p className="text-muted-foreground text-xs">
                      Reaction: {a.reactionText}
                    </p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      {manageOpen && (
        <AllergyManageModal
          tenantSlug={tenantSlug}
          patientId={patientId}
          items={allItems}
          onClose={() => setManageOpen(false)}
          onMutated={() =>
            queryClient.invalidateQueries({
              queryKey: ['allergies', tenantSlug, patientId],
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

function AllergyManageModal({
  tenantSlug,
  patientId,
  items,
  onClose,
  onMutated,
}: {
  tenantSlug: string
  patientId: string
  items: Allergy[]
  onClose: () => void
  onMutated: () => void
}): React.JSX.Element {
  const [substanceText, setSubstanceText] = useState('')
  const [severity, setSeverity] = useState<AllergySeverity>('MODERATE')
  const [reactionText, setReactionText] = useState('')
  const [addError, setAddError] = useState<string | null>(null)

  const addMutation = useMutation({
    mutationFn: () =>
      addAllergy({
        tenantSlug,
        patientId,
        substanceText: substanceText.trim(),
        severity,
        ...(reactionText.trim().length > 0
          ? { reactionText: reactionText.trim() }
          : {}),
      }),
    onSuccess: () => {
      setSubstanceText('')
      setReactionText('')
      setAddError(null)
      onMutated()
    },
    onError: (err) => {
      setAddError(addErrorMessage(err))
    },
  })

  const [transitionError, setTransitionError] = useState<string | null>(null)
  const [pendingAllergyId, setPendingAllergyId] = useState<string | null>(null)
  const transitionMutation = useMutation({
    mutationFn: (vars: {
      allergyId: string
      expectedRowVersion: number
      status: AllergyStatus
    }) =>
      updateAllergy({
        tenantSlug,
        patientId,
        allergyId: vars.allergyId,
        expectedRowVersion: vars.expectedRowVersion,
        status: vars.status,
      }),
    onSuccess: () => {
      setPendingAllergyId(null)
      setTransitionError(null)
      onMutated()
    },
    onError: (err) => {
      setPendingAllergyId(null)
      setTransitionError(transitionErrorMessage(err))
    },
  })

  function onTransition(a: Allergy, status: AllergyStatus): void {
    setTransitionError(null)
    setPendingAllergyId(a.id)
    transitionMutation.mutate({
      allergyId: a.id,
      expectedRowVersion: a.rowVersion,
      status,
    })
  }

  function onAdd(): void {
    if (substanceText.trim().length === 0) return
    setAddError(null)
    addMutation.mutate()
  }

  // Sorted by status priority for the management view (ACTIVE
  // first, INACTIVE next, ENTERED_IN_ERROR last) — server already
  // returns this order; the local sort is defensive.
  const sortedItems = useMemo<Allergy[]>(() => {
    const priority: Record<AllergyStatus, number> = {
      ACTIVE: 0,
      INACTIVE: 1,
      ENTERED_IN_ERROR: 2,
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
      aria-labelledby="allergy-manage-title"
      tabIndex={-1}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
      onClick={(e) => {
        // Backdrop click — only dismiss when the click is on the
        // overlay itself, not on the dialog content. Focus trap
        // is a known gap (no app-wide library yet) — tracked as
        // a follow-up.
        if (e.target === e.currentTarget) onClose()
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
      data-testid="allergy-manage-modal"
    >
      <div
        className="bg-background w-full max-w-2xl rounded-lg border p-6 shadow-lg"
        data-phi
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 id="allergy-manage-title" className="text-lg font-semibold">
            Manage allergies
          </h2>
          <Button
            variant="outline"
            size="sm"
            onClick={onClose}
            data-testid="allergy-manage-close"
          >
            Close
          </Button>
        </div>

        {/* Add form */}
        <div className="mb-6 flex flex-col gap-2 rounded-md border p-3">
          <label
            htmlFor="allergy-substance"
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Substance
          </label>
          <input
            id="allergy-substance"
            type="text"
            value={substanceText}
            onChange={(e) => setSubstanceText(e.target.value)}
            disabled={addMutation.isPending}
            maxLength={500}
            placeholder="e.g., Penicillin"
            data-testid="allergy-substance-input"
            className="border-input rounded-md border bg-transparent px-3 py-2 text-sm"
          />
          <label
            htmlFor="allergy-severity"
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Severity
          </label>
          <select
            id="allergy-severity"
            value={severity}
            onChange={(e) => setSeverity(e.target.value as AllergySeverity)}
            disabled={addMutation.isPending}
            data-testid="allergy-severity-select"
            className="border-input rounded-md border bg-transparent px-3 py-2 text-sm"
          >
            {SEVERITY_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <label
            htmlFor="allergy-reaction"
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Reaction (optional)
          </label>
          <textarea
            id="allergy-reaction"
            value={reactionText}
            onChange={(e) => setReactionText(e.target.value)}
            disabled={addMutation.isPending}
            rows={2}
            maxLength={4000}
            placeholder="e.g., hives, anaphylaxis…"
            data-testid="allergy-reaction-input"
            className="border-input rounded-md border bg-transparent px-3 py-2 text-sm"
          />
          {addError !== null && (
            <p
              role="alert"
              className="text-destructive text-xs"
              data-testid="allergy-add-error"
            >
              {addError}
            </p>
          )}
          <div className="flex justify-end">
            <Button
              size="sm"
              onClick={onAdd}
              disabled={
                addMutation.isPending || substanceText.trim().length === 0
              }
              data-testid="allergy-add-button"
            >
              {addMutation.isPending ? 'Adding…' : 'Add allergy'}
            </Button>
          </div>
        </div>

        {/* Existing allergies — full list (all statuses) */}
        {sortedItems.length === 0 ? (
          <p
            className="text-muted-foreground text-sm"
            data-testid="allergy-manage-empty"
          >
            No allergies on file yet.
          </p>
        ) : (
          <ul
            className="flex flex-col gap-2"
            data-testid="allergy-manage-list"
          >
            {sortedItems.map((a) => (
              <li
                key={a.id}
                className={
                  a.status === 'ACTIVE'
                    ? 'rounded-md border p-3'
                    : 'rounded-md border bg-muted/40 p-3 opacity-70'
                }
                data-testid="allergy-manage-row"
                data-allergy-id={a.id}
                data-allergy-status={a.status}
              >
                <div className="mb-1 flex items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium">{a.substanceText}</span>
                    <span className="bg-muted rounded-full px-2 py-0.5 text-xs font-semibold">
                      {severityLabel(a.severity)}
                    </span>
                    <span
                      className={
                        a.status === 'ACTIVE'
                          ? 'bg-primary/15 text-primary rounded-full px-2 py-0.5 text-xs font-semibold'
                          : a.status === 'INACTIVE'
                            ? 'bg-secondary text-secondary-foreground rounded-full px-2 py-0.5 text-xs font-semibold'
                            : 'bg-destructive/15 text-destructive rounded-full px-2 py-0.5 text-xs font-semibold'
                      }
                    >
                      {a.status === 'ENTERED_IN_ERROR' ? 'Entered in error' : a.status}
                    </span>
                  </div>
                  <div className="flex gap-2">
                    {a.status === 'ACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(a, 'INACTIVE')}
                        disabled={pendingAllergyId === a.id}
                        data-testid="allergy-deactivate-button"
                      >
                        Mark inactive
                      </Button>
                    )}
                    {a.status === 'INACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(a, 'ACTIVE')}
                        disabled={pendingAllergyId === a.id}
                        data-testid="allergy-reactivate-button"
                      >
                        Reactivate
                      </Button>
                    )}
                    {a.status !== 'ENTERED_IN_ERROR' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onTransition(a, 'ENTERED_IN_ERROR')}
                        disabled={pendingAllergyId === a.id}
                        data-testid="allergy-revoke-button"
                      >
                        Mark entered in error
                      </Button>
                    )}
                  </div>
                </div>
                {a.reactionText && (
                  <p className="text-muted-foreground text-xs">
                    Reaction: {a.reactionText}
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
            data-testid="allergy-transition-error"
          >
            {transitionError}
          </p>
        )}
      </div>
    </div>
  )
}

function addErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return 'You do not have authority to add allergies on this tenant.'
    }
    if (err.status === 422) {
      return 'Invalid allergy. Check substance and severity, then try again.'
    }
  }
  return 'Could not add allergy. Try again.'
}

function transitionErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return 'You do not have authority to update allergies on this tenant.'
    }
    if (err.status === 409) {
      const reason = extractConflictReason(err)
      if (reason === 'allergy_terminal') {
        return 'This allergy was already retracted. Refresh to see the current state.'
      }
      if (reason === 'stale_row') {
        return 'This allergy was changed by someone else. Refresh and try again.'
      }
      return 'This allergy state changed. Refresh and try again.'
    }
  }
  return 'Could not update allergy. Try again.'
}

function extractConflictReason(err: ApiError): string | null {
  if (typeof err.body !== 'object' || err.body === null) return null
  const details = (err.body as { details?: unknown }).details
  if (typeof details !== 'object' || details === null) return null
  const reason = (details as { reason?: unknown }).reason
  return typeof reason === 'string' ? reason : null
}
