package com.medcore.platform.security.phi

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [PhiRequestContextHolder] (Phase 4A.0).
 *
 * Pure ThreadLocal semantics — no Spring context.
 */
class PhiRequestContextHolderTest {

    private val holder = PhiRequestContextHolder()

    @AfterEach
    fun tearDown() {
        holder.clear()
    }

    @Test
    fun `empty holder returns null`() {
        assertThat(holder.get()).isNull()
    }

    @Test
    fun `set then get returns the context`() {
        val ctx = PhiRequestContext(
            userId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
        )
        holder.set(ctx)
        assertThat(holder.get()).isEqualTo(ctx)
    }

    @Test
    fun `clear removes the context`() {
        holder.set(PhiRequestContext(UUID.randomUUID(), UUID.randomUUID()))
        holder.clear()
        assertThat(holder.get()).isNull()
    }

    @Test
    fun `context does not leak across threads (ThreadLocal semantics)`() {
        // Parent thread sets context; child thread must see null.
        val parentCtx = PhiRequestContext(UUID.randomUUID(), UUID.randomUUID())
        holder.set(parentCtx)

        var childSaw: PhiRequestContext? = parentCtx  // sentinel — must be nulled by child
        val thread = Thread {
            childSaw = holder.get()
        }
        thread.start()
        thread.join()

        assertThat(childSaw).isNull()
        // Parent thread's context is still set.
        assertThat(holder.get()).isEqualTo(parentCtx)
    }
}
