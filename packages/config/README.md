# packages/config

Shared configuration for Medcore. This package is the single source of
truth for cross-cutting settings consumed by every app and package.

## Scope

`packages/config` holds configuration that MUST be identical across the
monorepo. If a setting varies per app, it does not belong here — it belongs
in the app that owns it.

Expected contents (added as phases land):

- **Lint and formatter presets** — ESLint, Prettier, Ruff, Stylelint, etc.
- **TypeScript base configs** — `tsconfig.base.json` and variants.
- **Environment variable schema** — a typed schema describing every
  environment variable the platform consumes, with classification,
  required / optional, and default where safe.
- **Bundle and performance budgets** — per-app thresholds enforced in CI.
- **Coverage thresholds** — per-package floors enforced in CI.
- **Feature flag declarations** — typed definitions for flags, consumed by
  apps at build time.

## Rules

1. This package is **configuration only**. It MUST NOT contain runtime
   logic, network calls, or side effects on import.
2. Every exported config MUST be typed and documented. A config value with
   no description is considered incomplete.
3. Changes that alter lint, coverage, bundle, or environment schema are
   governance changes and REQUIRE review by the owner, and an ADR when
   they relax a rule.
4. `.env` files MUST NOT live here. Only `.env.example` templates with safe
   placeholders and comments.
5. Secrets MUST NEVER be referenced here except as placeholder names.

## Environment variable policy

The environment schema (once introduced) MUST:

- Name every variable used by any app in the repo.
- Classify each variable: `secret`, `config`, `feature-flag`, `public`.
- Declare whether it is required, optional, or has a safe default.
- Fail application startup on missing required variables, with a clear
  error pointing to the schema.

Applications MUST validate their environment against this schema at
startup. Ad-hoc `process.env.X` reads in feature code are prohibited.

## Adding a config

1. Place the config in the appropriate subdirectory.
2. Type and document it.
3. Update this README if a new category of config is introduced.
4. Update CI to consume the new config where applicable.

## Forbidden

- Embedding secrets, API keys, or endpoints of specific environments.
- Overriding a tightening rule at the package level without an ADR.
- Duplicating config in individual apps that already lives here.
