import { afterEach, describe, expect, it } from 'vitest'

import { clearToken, getToken, setToken } from '@/lib/auth'

describe('auth token store', () => {
  afterEach(() => {
    clearToken()
  })

  it('starts with no token', () => {
    expect(getToken()).toBeNull()
  })

  it('stores a set token', () => {
    setToken('abc.def.ghi')
    expect(getToken()).toBe('abc.def.ghi')
  })

  it('treats empty string as cleared', () => {
    setToken('abc')
    setToken('')
    expect(getToken()).toBeNull()
  })

  it('never persists to localStorage or sessionStorage', () => {
    setToken('a-token')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})
