# apps/web

Medcore frontend — **React 19 + TypeScript 5.7 + Vite 6**. This is the
platform shell only: no router, no design system, no business pages,
no data fetching. Shared components land in `packages/ui/` as they are
introduced.

## Quickstart

From the repo root:

```bash
pnpm install         # first time, installs the whole workspace
make web-dev         # http://localhost:5173
make web-test        # Vitest (happy-dom)
make web-typecheck   # tsc --noEmit
make web-build       # static output under apps/web/dist/
```

## Layout

```
apps/web/
  index.html
  package.json
  tsconfig.json            # references tsconfig.app.json + tsconfig.node.json
  tsconfig.app.json
  tsconfig.node.json
  vite.config.ts
  src/
    main.tsx               # React entry; mounts <App />
    App.tsx                # shell component
    App.test.tsx           # smoke test
    index.css              # minimal reset + system fonts
    vite-env.d.ts
```

## Conventions

- Strict TypeScript: `strict`, `noUncheckedSideEffectImports`,
  `exactOptionalPropertyTypes`, `noImplicitOverride` are on and MUST stay on.
- All server calls MUST go through `packages/api-client` once it exists.
  Ad-hoc `fetch` / `axios` is prohibited (see
  `.cursor/rules/02-api-contracts.mdc`).
- PHI-rendering components MUST be tagged (see
  `.cursor/rules/04-frontend-standards.mdc`). No PHI rendering exists yet.
- Design tokens will live in `packages/ui/`. Do not hard-code colors,
  spacing, or typography in feature code once the token layer lands.

## Notes

- No ESLint / Prettier config is wired at the app level yet. Shared lint
  configuration will live in `packages/config/` and be consumed here;
  until then, rely on `typecheck` + editor defaults + the EditorConfig
  baseline at the repo root.
- No router is included. It will be added under a dedicated ADR when the
  first multi-page flow arrives.
