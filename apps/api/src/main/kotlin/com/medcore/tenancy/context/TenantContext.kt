package com.medcore.tenancy.context

import com.medcore.platform.tenancy.ResolvedMembership
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.stereotype.Component
import org.springframework.web.context.WebApplicationContext

/**
 * Request-scoped holder for the tenant membership resolved by
 * [TenantContextFilter]. May be empty when the request carries no
 * `X-Medcore-Tenant` header.
 *
 * Injectable as a scoped proxy — controllers and services can receive it
 * as a singleton-style bean and Spring delegates calls to the current
 * request's instance.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
class TenantContext {

    @Volatile
    private var resolved: ResolvedMembership? = null

    fun set(membership: ResolvedMembership) {
        resolved = membership
    }

    fun current(): ResolvedMembership? = resolved

    fun require(): ResolvedMembership =
        resolved ?: throw TenantContextMissingException(
            "TenantContext required but not set for this request",
        )

    val isSet: Boolean
        get() = resolved != null
}
