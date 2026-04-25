package com.medcore.clinical.allergy.read

import com.medcore.clinical.allergy.write.AllergySnapshot
import com.medcore.platform.read.pagination.PageResponse

/**
 * Handler result for [ListPatientAllergiesCommand]
 * (Phase 4E.1, paginated as of platform-pagination chunk D).
 *
 * Wire envelope: `{ items, pageInfo }` per ADR-009 §2.4.
 * `typealias` keeps the substrate as the single source of
 * truth for envelope shape.
 */
typealias ListPatientAllergiesResult = PageResponse<AllergySnapshot>
