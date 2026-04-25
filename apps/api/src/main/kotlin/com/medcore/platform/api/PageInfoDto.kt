package com.medcore.platform.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.medcore.platform.read.pagination.PageInfo

/**
 * Wire shape of the `pageInfo` field returned by every
 * paginated list endpoint (ADR-009 §2.4).
 *
 * Mirrors the substrate's
 * [com.medcore.platform.read.pagination.PageInfo] but lives in
 * `platform.api` because it's a wire-level DTO consumed by
 * every clinical `*ListResponse`. Keeping it in a shared
 * location avoids per-domain DTOs reaching across siblings —
 * `clinical.allergy.api` should not depend on
 * `clinical.encounter.api` (or vice versa) just to get a
 * cross-cutting envelope shape.
 *
 * `@JsonInclude(NON_NULL)` keeps `nextCursor` omitted on the
 * last page (the substrate guarantees it is null in lockstep
 * with `hasNextPage = false`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageInfoDto(
    val hasNextPage: Boolean,
    val nextCursor: String?,
) {
    companion object {
        fun from(pi: PageInfo): PageInfoDto =
            PageInfoDto(hasNextPage = pi.hasNextPage, nextCursor = pi.nextCursor)
    }
}
