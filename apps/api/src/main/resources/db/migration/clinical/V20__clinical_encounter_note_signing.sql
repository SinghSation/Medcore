-- V20__clinical_encounter_note_signing.sql — Phase 4D.5
--
-- Ships the note signing state machine on top of V19's append-only
-- note table. DRAFT → SIGNED is one-way; signed notes are
-- immutable. Enforced by a CHECK constraint (coherence) + a
-- BEFORE UPDATE trigger (immutability).
--
-- ### What this migration adds
--
--   - status TEXT NOT NULL DEFAULT 'DRAFT' — closed-enum lifecycle.
--   - signed_at TIMESTAMPTZ NULL — when the note was signed.
--   - signed_by UUID NULL — which user signed it.
--   - amends_id UUID NULL — FK to `clinical.encounter_note(id)`;
--     reserved for the future amendment workflow. In 4D.5 it is
--     NEVER populated — no write path sets it. Shipping now
--     avoids a second migration when the amendment slice lands.
--   - ck_clinical_encounter_note_status — closed-enum CHECK.
--   - ck_clinical_encounter_note_signed_fields_coherent —
--     status=DRAFT ↔ signed_at/by NULL; status=SIGNED ↔ both set.
--   - fk_clinical_encounter_note_amends — FK with ON DELETE
--     RESTRICT so future amendment chains cannot be orphaned.
--   - fn_clinical_encounter_note_immutable_once_signed() trigger
--     function — defense-in-depth against any UPDATE on a SIGNED
--     row. Fires BEFORE UPDATE on every row; if OLD.status =
--     'SIGNED', raises an exception. The legitimate signing
--     transition (DRAFT → SIGNED) passes because OLD.status is
--     'DRAFT' at that point.
--
-- ### Backfill for existing rows
--
-- Every existing `clinical.encounter_note` row pre-V20 was
-- written under the "no signing" regime. DEFAULT 'DRAFT' on the
-- status column backfills them cleanly. signed_at / signed_by /
-- amends_id stay NULL. No data migration needed.
--
-- ### Strictly additive + reversible
--
--   ALTER TABLE clinical.encounter_note
--     DROP CONSTRAINT fk_clinical_encounter_note_amends,
--     DROP CONSTRAINT ck_clinical_encounter_note_signed_fields_coherent,
--     DROP CONSTRAINT ck_clinical_encounter_note_status,
--     DROP COLUMN amends_id,
--     DROP COLUMN signed_by,
--     DROP COLUMN signed_at,
--     DROP COLUMN status;
--   DROP TRIGGER IF EXISTS tr_clinical_encounter_note_immutable_once_signed
--     ON clinical.encounter_note;
--   DROP FUNCTION IF EXISTS clinical.fn_clinical_encounter_note_immutable_once_signed();
--
-- ### Scope notes
--
-- NOT in 4D.5:
--   - Amendment workflow that populates amends_id (separate slice).
--   - DELETE-blocking for signed rows — V19 DELETE policy still
--     allows OWNER/ADMIN. Signed-row deletion is a separate
--     clinical-policy slice (SOC2 retention + HIPAA legal-hold).
--   - FHIR DocumentReference wire shape — Phase 5A.

-- -----------------------------------------------------------------------
-- New columns
-- -----------------------------------------------------------------------

ALTER TABLE clinical.encounter_note
    ADD COLUMN status        TEXT        NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN signed_at     TIMESTAMPTZ NULL,
    ADD COLUMN signed_by     UUID        NULL,
    ADD COLUMN amends_id     UUID        NULL;

-- -----------------------------------------------------------------------
-- Constraints
-- -----------------------------------------------------------------------

ALTER TABLE clinical.encounter_note
    ADD CONSTRAINT ck_clinical_encounter_note_status
        CHECK (status IN ('DRAFT', 'SIGNED'));

ALTER TABLE clinical.encounter_note
    ADD CONSTRAINT ck_clinical_encounter_note_signed_fields_coherent
        CHECK (
            (status = 'DRAFT'  AND signed_at IS NULL     AND signed_by IS NULL)
            OR
            (status = 'SIGNED' AND signed_at IS NOT NULL AND signed_by IS NOT NULL)
        );

ALTER TABLE clinical.encounter_note
    ADD CONSTRAINT fk_clinical_encounter_note_amends
        FOREIGN KEY (amends_id)
        REFERENCES clinical.encounter_note (id)
        ON DELETE RESTRICT;

-- -----------------------------------------------------------------------
-- Immutability trigger
-- -----------------------------------------------------------------------
--
-- Defense-in-depth. The handler already refuses to touch signed
-- rows, but direct SQL (DBA, future misbehaving handler) could
-- bypass the gate. The trigger makes "signed is immutable" a
-- DB-enforced invariant.
--
-- Allowed transitions:
--   - DRAFT → DRAFT (not exercised today; harmless)
--   - DRAFT → SIGNED (the signing event itself)
-- Refused transitions:
--   - SIGNED → SIGNED (re-sign — handler returns 409 first)
--   - SIGNED → DRAFT (un-sign — not a product capability)
--   - Any field change on a row with OLD.status = 'SIGNED'

CREATE OR REPLACE FUNCTION
    clinical.fn_clinical_encounter_note_immutable_once_signed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'SIGNED' THEN
        RAISE EXCEPTION
            'clinical.encounter_note is immutable once signed (id=%)',
            OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_clinical_encounter_note_immutable_once_signed
    ON clinical.encounter_note;
CREATE TRIGGER tr_clinical_encounter_note_immutable_once_signed
    BEFORE UPDATE ON clinical.encounter_note
    FOR EACH ROW
    EXECUTE FUNCTION clinical.fn_clinical_encounter_note_immutable_once_signed();
