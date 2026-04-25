-- V23__clinical_encounter_note_amendment_integrity.sql — Phase 4D.6
--
-- Defense-in-depth backstop for the note-amendment workflow that
-- ships in this slice. The handler is the user-facing error
-- surface; this trigger is the DB-layer integrity guarantee for
-- any write path that bypasses the handler (DBA SQL, future bug,
-- batch import).
--
-- ### Invariants enforced
--
--   1. **Single-level chains.** An amendment may reference only a
--      non-amendment note. If `NEW.amends_id` points at a row whose
--      own `amends_id IS NOT NULL`, the trigger refuses the INSERT.
--      Sibling amendments — multiple amendments referencing the
--      same original — ARE permitted; that's tracked by
--      `createdAt` ordering on the application side.
--
--   2. **Same-encounter rule.** An amendment must belong to the
--      same encounter as its original. If `NEW.encounter_id`
--      differs from the original's `encounter_id`, the trigger
--      refuses. This blocks cross-encounter data-integrity bugs
--      where note A's amendment ends up filed under encounter B.
--
-- ### What is NOT enforced here
--
--   - "Original must be SIGNED" — handler-only (clean 409 with
--     reason `cannot_amend_unsigned_note`). The DB trigger does
--     not need this guard because direct-SQL writers can already
--     bypass clinical workflow; the integrity invariants V23
--     enforces are about the *shape* of the FK graph, not the
--     status enum.
--
--   - Sibling-amendment ordering — application-only (`createdAt`).
--
--   - Body content / length — V19 governs that.
--
-- ### Trigger surface
--
-- BEFORE INSERT only:
--   - Amendments are immutable post-create per JPA (`amendsId`
--     is `updatable = false`); UPDATE never changes amends_id,
--     so an UPDATE trigger would be dead code.
--   - V20's `tr_clinical_encounter_note_immutable_once_signed`
--     already covers the "signed rows can't be UPDATEd at all"
--     surface — UPDATE protection is layered there, not here.
--
-- ### Strictly additive + reversible
--
--   DROP TRIGGER IF EXISTS tr_clinical_encounter_note_amendment_integrity
--     ON clinical.encounter_note;
--   DROP FUNCTION IF EXISTS clinical.fn_clinical_encounter_note_amendment_integrity();
--
-- ### Non-fires
--
-- The trigger short-circuits when `NEW.amends_id IS NULL` —
-- regular note creates pay nothing beyond a single NULL check.
-- Only amendment INSERTs touch the lookup query, and that lookup
-- hits the primary-key index on `clinical.encounter_note(id)`.

CREATE OR REPLACE FUNCTION
    clinical.fn_clinical_encounter_note_amendment_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_orig_amends      UUID;
    v_orig_encounter   UUID;
BEGIN
    IF NEW.amends_id IS NULL THEN
        -- Regular note insert; nothing to police.
        RETURN NEW;
    END IF;

    SELECT amends_id, encounter_id
      INTO v_orig_amends, v_orig_encounter
      FROM clinical.encounter_note
     WHERE id = NEW.amends_id;

    -- The FK constraint fk_clinical_encounter_note_amends ensures
    -- the row exists; if SELECT yields no row the FK has already
    -- raised. Defensive null-guards remain so a future FK relax
    -- can't silently bypass these checks.

    IF v_orig_amends IS NOT NULL THEN
        RAISE EXCEPTION
            'cannot amend an amendment (note=% targets amendment=%)',
            NEW.id, NEW.amends_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF v_orig_encounter IS NOT NULL
       AND v_orig_encounter <> NEW.encounter_id THEN
        RAISE EXCEPTION
            'amendment must belong to the same encounter as the original (note=% encounter=% original_encounter=%)',
            NEW.id, NEW.encounter_id, v_orig_encounter
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_clinical_encounter_note_amendment_integrity
    ON clinical.encounter_note;
CREATE TRIGGER tr_clinical_encounter_note_amendment_integrity
    BEFORE INSERT ON clinical.encounter_note
    FOR EACH ROW
    EXECUTE FUNCTION clinical.fn_clinical_encounter_note_amendment_integrity();
