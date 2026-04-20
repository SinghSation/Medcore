# Runbook — Local Development Setup

**Scope:** First-time setup for Medcore developers on macOS. Covers
`apps/api` (Kotlin + Spring Boot) and `apps/web` (React + Vite).
**Audience:** Any human or AI agent running the platform locally.
**Status:** Authoritative.

---

## 1. Prerequisites

Install once per machine. All commands assume Homebrew is present.

| Tool            | Minimum | Install                                                                                        |
| --------------- | ------- | ---------------------------------------------------------------------------------------------- |
| Git             | 2.40    | `brew install git`                                                                             |
| GitHub CLI      | 2.40    | `brew install gh` — then follow `github-auth.md`                                               |
| JDK 21          | 21      | `brew install openjdk@21` *(any Java 21 distribution works; Temurin `--cask temurin@21` is optional)* |
| Gradle          | 8.10    | `brew install gradle` *(only needed once, see §3.1)*                                           |
| Node.js         | 20 LTS  | `brew install node@20` *(or `node@22`; use `fnm` / `nvm` to switch)*                           |
| pnpm            | 9       | `corepack enable && corepack prepare pnpm@9 --activate`                                        |
| GNU Make        | 4.3     | `brew install make` *(macOS ships BSD Make; optional)*                                         |

Verify in one shot:

```bash
git --version && gh --version && java -version && gradle -v \
  && node --version && pnpm --version && make --version
```

Every tool MUST report at or above the minimum version.

## 2. Clone & authenticate

```bash
gh auth login                 # follow docs/runbooks/github-auth.md
gh repo clone SinghSation/Medcore
cd Medcore
```

## 3. One-time project setup

### 3.1 Backend — Gradle wrapper

The Gradle wrapper files (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`)
are intentionally not committed yet. Generate them once:

```bash
cd apps/api
gradle wrapper --gradle-version 8.11.1
cd -
```

After that, everyone uses `./gradlew …` — the host `gradle` is no longer
needed.

Commit the wrapper files in a dedicated, approved commit (ADR or
explicit operator instruction) — not in an unrelated feature change.

### 3.2 Frontend — install workspace dependencies

```bash
pnpm install
```

This installs everything declared under `apps/web` (and any future
workspace package) into the root `node_modules` using pnpm's content-
addressable store.

## 4. Running locally

Two terminals.

### Backend

```bash
make api-dev
```

- Starts Spring Boot on `http://localhost:8080`.
- Verify by watching for the log line
  `Started MedcoreApiApplication in <n>s`. No HTTP endpoints are
  exposed at bootstrap, so `curl http://localhost:8080/` will return
  404 — that is expected.

### Frontend

```bash
make web-dev
```

- Starts Vite on `http://localhost:5173` (strict port — no auto-shift).
- The shell displays `Medcore — Platform shell online.` — that is the
  intended output at bootstrap.

The frontend does not yet talk to the backend. The API client and
CORS configuration will land with the first real endpoint, under an
ADR.

## 5. Testing

```bash
make api-test        # Kotlin + JUnit 5, boots Spring context
make web-test        # Vitest + happy-dom
make test            # both
```

## 6. Type checking and build

```bash
make web-typecheck   # tsc --noEmit
make api-build       # apps/api/build/libs/*.jar
make web-build       # apps/web/dist/
```

## 7. Troubleshooting

- **`gradle: command not found`:** see §1. You only need host `gradle`
  once, to generate the wrapper.
- **`./gradlew: Permission denied`:** `chmod +x apps/api/gradlew`.
- **`port 8080 in use`:** set `SERVER_PORT=8090 make api-dev` (Spring
  reads `SERVER_PORT`) or stop the conflicting process.
- **`port 5173 in use`:** the config uses `strictPort: true` so Vite
  will fail rather than silently shift. Stop the conflicting process.
- **`pnpm: command not found`:** re-run
  `corepack enable && corepack prepare pnpm@9 --activate`.
- **Gradle refuses to download a JDK:** the `foojay-resolver` plugin
  pulls a matching JDK on demand; if blocked by a corporate proxy,
  install Temurin 21 via Homebrew and retry.

## 8. What this runbook does NOT do

This runbook is for **local development** only. It does not cover:

- CI configuration — tracked under `.github/workflows/` (not yet wired).
- Staging or production deployment — tracked under `infra/terraform/`
  (not yet wired).
- Secrets management — tracked under a future runbook; current rule:
  never commit secrets (see `.cursor/rules/01-security-invariants.mdc`).

## 9. Related

- `docs/runbooks/github-auth.md` — git credential setup.
- `AGENTS.md` §4.7 — controlled commit authority.
- `.claude/skills/safe-local-commit.md` — pre-commit procedure.
- `apps/api/README.md`, `apps/web/README.md` — per-app notes.
