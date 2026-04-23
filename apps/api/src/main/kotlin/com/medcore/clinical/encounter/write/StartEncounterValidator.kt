package com.medcore.clinical.encounter.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [StartEncounterCommand] (Phase 4C.1,
 * VS1 Chunk D).
 *
 * The controller's `@PathVariable UUID` binding guarantees
 * `patientId` is a syntactically valid UUID by the time this
 * validator runs; the binding also guarantees `slug` is
 * non-blank from the URL template.
 *
 * `encounterClass` can only be a single closed-enum value
 * (`AMB`) in 4C.1, so Jackson's enum deserialization covers
 * the "unknown class" case at the DTO layer
 * ([com.medcore.clinical.encounter.api.StartEncounterRequest.toCommand]).
 *
 * This validator is therefore almost empty today. It exists for
 * two reasons:
 *
 * 1. **Pattern discipline** — clinical-write-pattern v1.3 §3
 *    requires every command to have a validator. Skipping it
 *    because "there's nothing to validate today" would produce
 *    drift the moment a later slice adds a new field.
 * 2. **Future-proofing** — when later 4C slices add scheduling
 *    windows, provider attribution, or appointment linkage,
 *    their cross-field invariants land here.
 */
@Component
class StartEncounterValidator : WriteValidator<StartEncounterCommand> {

    override fun validate(command: StartEncounterCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }
    }
}
