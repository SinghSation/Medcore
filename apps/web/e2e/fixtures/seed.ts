/**
 * Admin-scoped Postgres seed for Playwright E2E (VS1 Chunk F).
 *
 * Why direct JDBC-style inserts and not the API write paths:
 *   - The test is about the encounter + note workflow, not
 *     patient onboarding.
 *   - Admin inserts are ~50× faster than the full POST write path.
 *   - Every E2E run seeds a deterministic baseline so the 90 s /
 *     3-click DoDs are repeatable.
 *
 * ### Isolation posture
 *
 * The seed lives inside a dedicated tenant slug ({@link E2E_TENANT_SLUG}).
 * Cleanup ONLY scopes to rows tagged with that tenant id. Audit rows
 * are NEVER deleted — `audit.audit_event` is append-only per ADR-003
 * and the audit chain would break if we truncated it.
 *
 * ### PHI posture
 *
 * The seeded patient uses distinctive sentinel strings for name /
 * MRN / birth date so the `phi-leakage.spec.ts` scan has something
 * concrete to search for. These values are NOT real PHI — they're
 * test-only strings that the leakage scan asserts must never appear
 * in browser storage / cookies / console output.
 */

import { randomUUID } from 'node:crypto'
import { Client } from 'pg'

export const E2E_TENANT_SLUG = 'e2e-test'
export const E2E_TENANT_DISPLAY = 'E2E Test Tenant'
export const E2E_PATIENT_MRN = 'E2E-0000001'
export const E2E_PATIENT_NAME_GIVEN = 'Playwright'
export const E2E_PATIENT_NAME_FAMILY = 'TestPatient-XYZABC'
export const E2E_PATIENT_BIRTH_DATE = '1985-06-15'
export const E2E_PATIENT_SEX = 'female'

export interface SeededState {
  tenantId: string
  tenantSlug: string
  patientId: string
  userId: string
}

function pgEnv(): {
  host: string
  port: number
  user: string
  password: string
  database: string
} {
  const password = process.env.POSTGRES_PASSWORD
  if (!password) {
    throw new Error(
      'POSTGRES_PASSWORD is required in the E2E shell env. ' +
        'Run `set -a && source .env && set +a` before `make e2e`.',
    )
  }
  return {
    host: process.env.POSTGRES_HOST ?? 'localhost',
    port: Number(process.env.POSTGRES_PORT ?? 15432),
    user: process.env.POSTGRES_USER ?? 'medcore',
    password,
    database: process.env.POSTGRES_DB ?? 'medcore_dev',
  }
}

/**
 * Seed a clean baseline for an E2E run: one tenant, one OWNER
 * membership for the given user, one patient.
 *
 * Caller must pass the user id returned by a prior `/api/v1/me`
 * call — we do NOT JIT-provision the user here (that's a
 * production API-side concern, not a seeder concern).
 */
export async function seedE2eBaseline(userId: string): Promise<SeededState> {
  const client = new Client(pgEnv())
  await client.connect()
  try {
    const tenantId = await resetAndCreateTenant(client)
    await createOwnerMembership(client, tenantId, userId)
    const patientId = await createPatient(client, tenantId, userId)
    return {
      tenantId,
      tenantSlug: E2E_TENANT_SLUG,
      patientId,
      userId,
    }
  } finally {
    await client.end()
  }
}

async function resetAndCreateTenant(client: Client): Promise<string> {
  const existing = await client.query<{ id: string }>(
    'SELECT id FROM tenancy.tenant WHERE slug = $1',
    [E2E_TENANT_SLUG],
  )
  if (existing.rowCount && existing.rows[0]) {
    const existingId = existing.rows[0].id
    await cascadeDeleteNonAuditTenantData(client, existingId)
    await client.query('DELETE FROM tenancy.tenant WHERE id = $1', [existingId])
  }
  const id = randomUUID()
  await client.query(
    `INSERT INTO tenancy.tenant(
       id, slug, display_name, status, created_at, updated_at, row_version
     ) VALUES ($1, $2, $3, 'ACTIVE', now(), now(), 0)`,
    [id, E2E_TENANT_SLUG, E2E_TENANT_DISPLAY],
  )
  return id
}

/**
 * Scope-limited cleanup: everything tied to this tenant except
 * audit rows. Audit survives per ADR-003 §2 (append-only chain).
 */
async function cascadeDeleteNonAuditTenantData(
  client: Client,
  tenantId: string,
): Promise<void> {
  // Problems before allergies — both FK to clinical.patient
  // (default RESTRICT). 4E.2 introduced the problem table; the
  // ordering between problem and allergy is not load-bearing
  // (neither references the other), but keeping a deterministic
  // order matches the per-test cleanup discipline in the API
  // integration tests.
  await client.query(
    `DELETE FROM clinical.problem WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.allergy WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.encounter_note WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.encounter WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.patient_identifier
       WHERE patient_id IN (SELECT id FROM clinical.patient WHERE tenant_id = $1)`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.patient_mrn_counter WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM clinical.patient WHERE tenant_id = $1`,
    [tenantId],
  )
  await client.query(
    `DELETE FROM tenancy.tenant_membership WHERE tenant_id = $1`,
    [tenantId],
  )
}

async function createOwnerMembership(
  client: Client,
  tenantId: string,
  userId: string,
): Promise<void> {
  await client.query(
    `INSERT INTO tenancy.tenant_membership(
       id, tenant_id, user_id, role, status,
       created_at, updated_at, row_version
     ) VALUES ($1, $2, $3, 'OWNER', 'ACTIVE', now(), now(), 0)`,
    [randomUUID(), tenantId, userId],
  )
}

/**
 * Per-test reset of encounter + note rows for the E2E tenant.
 *
 * Global setup seeds the tenant / patient / membership once per
 * suite. Individual specs land rows (IN_PROGRESS encounters,
 * notes) that MUST NOT leak into sibling specs — 4C.4's per-
 * patient IN_PROGRESS uniqueness invariant (V22) turns any such
 * leak into a UI state mismatch (Resume button instead of Start).
 *
 * Call this from a `beforeEach` in every spec that touches
 * encounters. Audit rows survive per ADR-003 §2 (append-only
 * chain).
 */
export async function resetEncountersForE2eTenant(): Promise<void> {
  const client = new Client(pgEnv())
  await client.connect()
  try {
    const existing = await client.query<{ id: string }>(
      'SELECT id FROM tenancy.tenant WHERE slug = $1',
      [E2E_TENANT_SLUG],
    )
    if (!existing.rowCount || !existing.rows[0]) {
      return
    }
    const tenantId = existing.rows[0].id
    // Problems + allergies first — both FK to patient
    // (RESTRICT). We never delete patients here, but
    // ordering them before encounter_note keeps a future
    // "delete patients too" addition safe by inspection.
    // 4E.2 added clinical.problem (V25); the ordering
    // between problem and allergy is independent (neither
    // references the other).
    await client.query(
      `DELETE FROM clinical.problem WHERE tenant_id = $1`,
      [tenantId],
    )
    await client.query(
      `DELETE FROM clinical.allergy WHERE tenant_id = $1`,
      [tenantId],
    )
    await client.query(
      `DELETE FROM clinical.encounter_note WHERE tenant_id = $1`,
      [tenantId],
    )
    await client.query(
      `DELETE FROM clinical.encounter WHERE tenant_id = $1`,
      [tenantId],
    )
  } finally {
    await client.end()
  }
}

async function createPatient(
  client: Client,
  tenantId: string,
  userId: string,
): Promise<string> {
  const id = randomUUID()
  await client.query(
    `INSERT INTO clinical.patient(
       id, tenant_id, mrn, mrn_source,
       name_given, name_family,
       birth_date, administrative_sex,
       status,
       created_by, updated_by,
       created_at, updated_at, row_version
     ) VALUES (
       $1, $2, $3, 'IMPORTED',
       $4, $5,
       $6::date, $7,
       'ACTIVE',
       $8, $8,
       now(), now(), 0
     )`,
    [
      id,
      tenantId,
      E2E_PATIENT_MRN,
      E2E_PATIENT_NAME_GIVEN,
      E2E_PATIENT_NAME_FAMILY,
      E2E_PATIENT_BIRTH_DATE,
      E2E_PATIENT_SEX,
      userId,
    ],
  )
  return id
}
