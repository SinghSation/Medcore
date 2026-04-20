# scripts/

Developer and CI automation scripts for Medcore. Scripts in this directory
support local workflows, CI checks, and one-off operator utilities. They
are **not** application code and MUST NOT be imported by `apps/` or
`packages/`.

## Rules

1. Every script MUST be invoked either by the root `Makefile` or by a
   documented CI workflow. Orphan scripts are removed.
2. Scripts MUST be idempotent. Re-running a script MUST NOT corrupt local
   state.
3. Scripts MUST NOT mutate non-local environments. Any script that talks to
   staging or production REQUIRES an ADR and lives under
   `scripts/operators/` (to be created when needed).
4. Scripts MUST read configuration from environment variables, not from
   files outside the repository. No hard-coded paths.
5. Shell scripts use `#!/usr/bin/env bash` with `set -euo pipefail` at the
   top. Python scripts declare a minimum Python version.
6. Secrets MUST NOT be embedded. Load from the approved secret manager.
7. Scripts MUST fail fast with a clear error if preconditions are not met.
8. Every script begins with a header comment describing:
   - Purpose.
   - Inputs (env vars, args).
   - Side effects.
   - Exit codes.

## Conventions

- Filenames use kebab-case: `phi-scan.sh`, `gen-openapi.sh`.
- Shell scripts are linted with `shellcheck` in CI.
- Python scripts use the same linters as application Python (declared in
  `packages/config/`).

## Adding a script

1. Write the header comment first.
2. Wire it into the `Makefile` as a named target.
3. Add tests or a dry-run mode where practical.
4. Update this README if the category of scripts changes.

## Forbidden

- Scripts that bypass governance (skip tests, disable hooks, push to
  protected branches).
- Scripts that read or write PHI.
- Scripts that install system packages without explicit human approval.
- Interactive scripts that cannot be run non-interactively in CI.
