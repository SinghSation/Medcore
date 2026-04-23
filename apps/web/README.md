# apps/web

Medcore frontend — **React 19 + TypeScript 5.7 + Vite 6** with
**Tailwind v4**, **shadcn/ui** primitives, **React Router v6**, and
**TanStack Query v5**. Demo shell for Vertical Slice 1 (Login → List →
View → Start Encounter → Write Note).

## Quickstart

From the repo root:

```bash
pnpm install         # first time, installs the whole workspace
make web-dev         # http://localhost:5173 (proxies /api and /fhir to :8080)
make web-test        # Vitest (happy-dom)
make web-typecheck   # tsc --noEmit
make web-build       # static output under apps/web/dist/
```

## Demo sign-in

The login page accepts a raw bearer token (paste flow — no backend
token endpoint). Get a token from the `medcore-mock-idp` container
or an issuer the API trusts, paste it, and click **Sign in**. The
token is held **in memory only**: refreshing the tab clears it on
purpose (demo discipline).

## Layout

```
apps/web/
  components.json            # shadcn/ui config
  index.html
  package.json
  tsconfig.json              # references tsconfig.app.json + tsconfig.node.json
  tsconfig.app.json          # strict TS, path alias @/* -> src/*
  tsconfig.node.json
  vite.config.ts             # vite + tailwindcss plugin + dev proxy
  src/
    main.tsx                 # React entry; wires providers + router
    index.css                # Tailwind v4 + shadcn CSS variables
    test-setup.ts            # @testing-library/jest-dom
    vite-env.d.ts
    components/ui/           # shadcn primitives (button, card, input, label)
    lib/                     # utils, api-client, auth, identity, tenancy
    pages/                   # LoginPage, HomePage, …
    providers/               # AuthProvider, QueryProvider
    routes/                  # AppRouter, ProtectedRoute
```

## Conventions

- **Strict TypeScript**: `strict`, `noUncheckedSideEffectImports`,
  `exactOptionalPropertyTypes`, `noImplicitOverride` are on and MUST
  stay on.
- **All server calls go through `src/lib/api-client.ts`.** The client
  attaches the current bearer token, sets JSON headers, unwraps the
  `{data, requestId}` envelope, and throws typed `ApiError`s on
  non-2xx. Ad-hoc `fetch` / `axios` is prohibited.
- **PHI discipline (frontend-phi-pattern.md lands with Chunk G):**
  - Never log request/response bodies. The API client is silent by
    design; callers that log MUST redact first.
  - No PHI in `localStorage`, `sessionStorage`, `document.title`, or
    URL path/query. Tokens live **in memory only** (see
    `src/lib/auth.ts`).
  - PHI-rendering components MUST be called out in code review.
- **Styling** uses shadcn/ui primitives on top of Tailwind v4. Prefer
  composing existing primitives (`Button`, `Card`, `Input`, `Label`)
  over hand-rolling utility stacks.

## Notes

- No ESLint / Prettier config is wired at the app level yet. Rely on
  `typecheck` + editor defaults + the EditorConfig baseline at the
  repo root until shared lint configuration lands in `packages/config/`.
