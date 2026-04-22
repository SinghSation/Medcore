package com.medcore.platform.api

import com.medcore.platform.observability.MdcKeys
import org.slf4j.MDC

/**
 * Shared construction helpers for [ErrorResponse]. Every handler that
 * emits an error envelope routes through these so the `requestId`
 * field is always populated from MDC (Phase 3F.1 substrate) without
 * handler code having to know about MDC directly.
 *
 * Kept intentionally minimal — two functions. Anything more elaborate
 * (localization, templating, cause-translation) belongs in a handler,
 * not here.
 */
object ErrorResponses {

    /**
     * Produces an [ErrorResponse] with `requestId` lifted from MDC.
     * `details` defaults to null; see [ErrorResponse]'s discipline.
     */
    fun of(code: String, message: String, details: Map<String, Any>? = null): ErrorResponse =
        ErrorResponse(
            code = code,
            message = message,
            requestId = MDC.get(MdcKeys.REQUEST_ID)?.takeIf { it.isNotBlank() },
            details = details,
        )
}
