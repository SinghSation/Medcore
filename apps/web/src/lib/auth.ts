/**
 * Demo-only auth state.
 *
 * Tokens live in a module-scoped variable — *not* localStorage,
 * not sessionStorage. Refresh clears the session on purpose: a
 * paste-token demo should not leave credential residue in browser
 * storage inspectable after close.
 *
 * This module is the single shared source of truth for the
 * current bearer token so that `api-client.ts` can read it
 * outside of React render trees.
 */

let currentToken: string | null = null

export function getToken(): string | null {
  return currentToken
}

export function setToken(token: string | null): void {
  currentToken = token && token.length > 0 ? token : null
}

export function clearToken(): void {
  currentToken = null
}
