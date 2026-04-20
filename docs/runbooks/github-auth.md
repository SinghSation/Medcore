# Runbook — GitHub Authentication

**Scope:** Medcore developer and CI workstations on macOS.
**Audience:** Every human or AI agent that runs `git` against `origin`.
**Status:** Authoritative.

Medcore uses **GitHub HTTPS authentication via the GitHub CLI (`gh`)**.
Credentials are stored in the **macOS Keychain**, never in the repository.
SSH and raw Personal Access Tokens are not the default sanctioned path
for Medcore macOS workstations unless explicitly approved by ADR or
operator instruction.

---

## 1. Install prerequisites

```bash
# GitHub CLI (once per machine)
brew install gh

# Verify
gh --version
```

`gh` MUST be version **2.40** or newer.

## 2. Authenticate

```bash
gh auth login
```

Answer the prompts:

- **What account?** → `GitHub.com`
- **Protocol for Git?** → `HTTPS`
- **Authenticate Git with your GitHub credentials?** → `Yes`
- **How would you like to authenticate?** → `Login with a web browser`
  (preferred; avoids pasting any token into the terminal)

When the browser opens, grant the following scopes:

- `repo` — read/write repository contents.
- `workflow` — **REQUIRED** for any commit that creates, modifies, or
  deletes a file under `.github/workflows/`. Without this scope, GitHub
  rejects the push with `refusing to allow a Personal Access Token to
  create or update workflow ... without 'workflow' scope`.
- `read:org` — optional; only if you need org listings locally.

Do NOT grant broader scopes (`admin:*`, `delete_repo`) unless explicitly
required by an operator task.

## 3. Wire `git` to `gh`

```bash
gh auth setup-git
```

This configures git's HTTPS credential helper to delegate to `gh`, which
reads from the macOS Keychain. No token value lands in any file you
control.

## 4. Verify

```bash
gh auth status
# Expected:
#   Logged in to github.com as <your-username> (keyring)
#   Git operations for github.com configured to use https protocol.
#   Token scopes: 'repo', 'workflow', ...
```

Verify git itself can authenticate against the remote without prompting:

```bash
# From the repo root:
git ls-remote origin >/dev/null && echo "auth OK" || echo "auth FAILED"
```

A trivial end-to-end check:

```bash
git fetch origin
git push --dry-run origin <current-branch>
```

The dry-run MUST complete without a credential prompt and without a
remote-side rejection.

## 5. Permission expectations when touching CI

Any commit that adds, edits, or removes a file under `.github/workflows/`
REQUIRES the **`workflow`** scope on the credential performing the push.
This applies even to seemingly trivial changes such as adding or removing
a `.gitkeep` placeholder.

If a push is rejected with the `workflow scope` error:

1. Do NOT bypass the check. Do NOT remove the workflow file solely to
   make the push succeed.
2. Run `gh auth refresh -h github.com -s workflow` to add the scope to
   the existing credential, or re-run `gh auth login` and select the
   `workflow` scope.
3. Re-verify with `gh auth status` — the Token scopes line MUST list
   `workflow`.
4. Re-push.

## 6. Credential rotation

Rotate the underlying GitHub credential at least **every 90 days**, and
**immediately** in any of the following cases:

- Token exposed in a chat, log, screenshot, ticket, transcript, or any
  place outside the Keychain.
- Token committed to any repository (public or private).
- Machine lost, stolen, or decommissioned.
- Team offboarding for the human owner of the credential.

Rotation procedure:

```bash
# 1. Revoke the current credential.
#    Browser:  https://github.com/settings/tokens  →  Revoke
#    or, if the credential was created by gh auth login, simply:
gh auth logout -h github.com

# 2. Authenticate freshly with the sanctioned flow.
gh auth login                # choose HTTPS + browser, grant repo + workflow
gh auth setup-git

# 3. Confirm.
gh auth status
git fetch origin
```

Rotation MUST NOT leave the old credential valid while the new one is in
use. Two simultaneously valid credentials is a forbidden state.

## 7. Non-negotiable rules

Agents and humans MUST NOT:

- **Commit** a GitHub token, SSH private key, `gh` config file, or any
  credential material to this repository, to `docs/`, to `.cursor/`, or
  to `.claude/`.
- **Paste** a credential value into the terminal, a chat, a ticket, or
  any AI assistant prompt. Use the `gh auth login` browser flow, which
  never exposes the token to the shell.
- **Store** a credential in assistant memory, agent prompts, skill
  files, or any session-persistent location readable by an AI tool.
- **Share** a credential across humans. Each operator holds their own.
- **Use** a credential without the minimum required scopes, or with
  scopes broader than needed.
- **Disable** `gh`'s Keychain-backed helper in favor of plaintext
  storage (`credential.helper store`, `.netrc`, or similar).

If a credential is ever observed in a repo, a diff, a log, or a
transcript, it MUST be treated as compromised and rotated under §6
without delay.

## 8. If something goes wrong

- **Push rejected — `workflow` scope missing:** see §5.
- **`gh auth status` shows "not logged in":** re-run `gh auth login`
  (§2).
- **`git` prompts for username/password:** the credential helper is not
  wired — run `gh auth setup-git` (§3).
- **Keychain prompts repeatedly:** unlock the login keychain in
  Keychain Access, or run `security unlock-keychain login.keychain`.
- **Suspected compromise:** rotate immediately (§6) and open a
  governance incident referencing this runbook.

## 9. Related

- `AGENTS.md` §3.1 — Security & PHI invariants.
- `.cursor/rules/01-security-invariants.mdc` — Secrets handling.
- `.claude/skills/safe-local-commit.md` — Forbidden-content scan at
  commit time.
