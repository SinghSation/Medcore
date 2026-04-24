-- V21__clinical_encounter_lifecycle.sql — Phase 4C.5
--
-- Closes out the encounter state machine in Medcore. Adds the
-- CANCEL-transition surface on top of V18's
-- `clinical.encounter` and enforces three invariants in SQL that
-- the Phase 4C.5 handlers also enforce in Kotlin:
--
--   1. Lifecycle-field coherence: each status value implies a
--      specific combination of timestamp columns, enforced by a
--      CHECK. Replaces V18's looser coherence check.
--   2. Cancel-reason is a closed enum matching the
--      `CancelReason` Kotlin enum.
--   3. Terminal states (FINISHED, CANCELLED) are immutable: any
--      UPDATE on a row whose PRE-image has
--      `status IN ('FINISHED','CANCELLED')` is refused by a
--      BEFORE UPDATE trigger. Mirrors the V20 note-signing
--      immutability pattern.
--
-- ### What this migration adds
--
--   - `cancelled_at TIMESTAMPTZ NULL`
--   - `cancel_reason TEXT NULL` with CHECK enum
--     {NO_SHOW, PATIENT_DECLINED, SCHEDULING_ERROR, OTHER}
--   - Replaces `ck_clinical_encounter_lifecycle_coherence`
--     with a stricter version that also covers the two new
--     columns. DROP-and-re-ADD is safe because no shipped row
--     is CANCELLED yet (the write surface hasn't existed).
--   - `fn_clinical_encounter_immutable_once_closed()` trigger
--     function + BEFORE UPDATE trigger on
--     `clinical.encounter`.
--
-- ### Backfill for existing rows
--
-- Every existing `clinical.encounter` row pre-V21 is
-- IN_PROGRESS (the only state the write surface produced).
-- The new columns default to NULL which matches IN_PROGRESS
-- in the new coherence CHECK. No data migration needed.
--
-- ### Strictly additive + reversible
--
-- Rollback:
--   DROP TRIGGER IF EXISTS tr_clinical_encounter_immutable_once_closed
--     ON clinical.encounter;
--   DROP FUNCTION IF EXISTS
--     clinical.fn_clinical_encounter_immutable_once_closed();
--   ALTER TABLE clinical.encounter
--     DROP CONSTRAINT ck_clinical_encounter_lifecycle_coherence,
--     DROP CONSTRAINT ck_clinical_encounter_cancel_reason,
--     DROP COLUMN cancel_reason,
--     DROP COLUMN cancelled_at;
--   -- then re-ADD V18's looser coherence CHECK if needed.
--
-- ### Scope notes
--
-- NOT in 4C.5:
--   - `entered-in-error` terminal state — roadmap non-goal.
--   - Reopening a FINISHED / CANCELLED encounter — not on
--     roadmap; closed is closed.
--   - DELETE-blocking for closed encounters — V18 DELETE
--     policy unchanged; deletion is not a product capability
--     anyway.

-- -----------------------------------------------------------------------
-- New columns
-- -----------------------------------------------------------------------

ALTER TABLE clinical.encounter
    ADD COLUMN cancelled_at  TIMESTAMPTZ NULL,
    ADD COLUMN cancel_reason TEXT        NULL;

-- -----------------------------------------------------------------------
-- Cancel-reason closed enum
-- -----------------------------------------------------------------------

ALTER TABLE clinical.encounter
    ADD CONSTRAINT ck_clinical_encounter_cancel_reason
        CHECK (
            cancel_reason IS NULL
            OR cancel_reason IN ('NO_SHOW', 'PATIENT_DECLINED', 'SCHEDULING_ERROR', 'OTHER')
        );

-- -----------------------------------------------------------------------
-- Replace lifecycle-coherence CHECK with 4C.5-aware version
-- -----------------------------------------------------------------------
--
-- Old V18 CHECK permitted CANCELLED with any timestamp state —
-- it had to, since `cancelled_at` + `cancel_reason` didn't
-- exist yet. The new CHECK pins the full shape.

ALTER TABLE clinical.encounter
    DROP CONSTRAINT ck_clinical_encounter_lifecycle_coherence;

ALTER TABLE clinical.encounter
    ADD CONSTRAINT ck_clinical_encounter_lifecycle_coherence
        CHECK (
            (status = 'PLANNED'
                AND started_at IS NULL
                AND finished_at IS NULL
                AND cancelled_at IS NULL
                AND cancel_reason IS NULL)
            OR
            (status = 'IN_PROGRESS'
                AND started_at IS NOT NULL
                AND finished_at IS NULL
                AND cancelled_at IS NULL
                AND cancel_reason IS NULL)
            OR
            (status = 'FINISHED'
                AND started_at IS NOT NULL
                AND finished_at IS NOT NULL
                AND cancelled_at IS NULL
                AND cancel_reason IS NULL)
            OR
            (status = 'CANCELLED'
                AND finished_at IS NULL
                AND cancelled_at IS NOT NULL
                AND cancel_reason IS NOT NULL)
        );

-- -----------------------------------------------------------------------
-- Immutability trigger for terminal states
-- -----------------------------------------------------------------------
--
-- Defense-in-depth. Handlers refuse to mutate closed encounters
-- (returning 409 `resource.conflict` with `details.reason:
-- encounter_already_closed`), but direct SQL or a future
-- misbehaving handler could bypass the gate. The trigger makes
-- "closed is immutable" a DB-enforced invariant.
--
-- Mirrors V20's note-signing trigger:
--   - Allowed: OLD.status = IN_PROGRESS → NEW.status =
--     FINISHED | CANCELLED (legitimate transitions).
--   - Allowed: OLD.status = PLANNED → NEW.status =
--     IN_PROGRESS | CANCELLED (legitimate transitions — no
--     PLANNED write path yet, but the shape is correct for
--     future scheduling slice).
--   - Refused: OLD.status = FINISHED or CANCELLED → any
--     UPDATE, including metadata-only touches.

CREATE OR REPLACE FUNCTION
    clinical.fn_clinical_encounter_immutable_once_closed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('FINISHED', 'CANCELLED') THEN
        RAISE EXCEPTION
            'clinical.encounter is immutable once closed (id=%, status=%)',
            OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_clinical_encounter_immutable_once_closed
    ON clinical.encounter;
CREATE TRIGGER tr_clinical_encounter_immutable_once_closed
    BEFORE UPDATE ON clinical.encounter
    FOR EACH ROW
    EXECUTE FUNCTION clinical.fn_clinical_encounter_immutable_once_closed();
