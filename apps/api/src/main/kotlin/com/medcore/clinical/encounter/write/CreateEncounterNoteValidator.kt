package com.medcore.clinical.encounter.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [CreateEncounterNoteCommand]
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Enforces the body invariants documented in V19:
 *   - non-empty after trim (a whitespace-only "note" is not a
 *     note)
 *   - max 20,000 chars (matches the `ck_clinical_encounter_note_body_length`
 *     CHECK constraint; keeps bounds in sync between app and DB)
 *
 * Clinical notes legitimately contain newlines, tabs, and other
 * whitespace. Unlike demographic-field validators (e.g.
 * `CreatePatientValidator`), this validator does NOT strip
 * control characters — doing so would corrupt legitimate note
 * formatting.
 */
@Component
class CreateEncounterNoteValidator : WriteValidator<CreateEncounterNoteCommand> {

    override fun validate(command: CreateEncounterNoteCommand) {
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
