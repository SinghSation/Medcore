package com.medcore.clinical.encounter.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [AmendNoteCommand] (Phase 4D.6).
 *
 * Mirrors `CreateEncounterNoteValidator` byte-for-byte on the
 * body invariants — amendments share V19's
 * `ck_clinical_encounter_note_body_length` bound (20000 chars)
 * because they go into the same table column. Whitespace-only
 * bodies are rejected as `body=required`; over-length bodies as
 * `body=max_length`.
 *
 * The body MUST stand on its own as a clinical note, not a diff
 * against the original — the wire model is "new note that
 * references the original via amends_id," not "patch payload."
 * That keeps audit + retrieval simple: each amendment is a
 * complete, signable note.
 */
@Component
class AmendNoteValidator : WriteValidator<AmendNoteCommand> {

    override fun validate(command: AmendNoteCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }
        val trimmed = command.body.trim()
        if (trimmed.isEmpty()) {
            throw WriteValidationException(field = "body", code = "required")
        }
        if (command.body.length > MAX_BODY_LENGTH) {
            throw WriteValidationException(field = "body", code = "max_length")
        }
    }

    private companion object {
        const val MAX_BODY_LENGTH: Int = 20_000
    }
}
