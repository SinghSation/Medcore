# ADR-010: AWS dev baseline + Terraform-managed infrastructure

- **Status:** Accepted
- **Date:** 2026-04-25
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh
- **Supersedes:** none
- **Related:** ADR-006 (production secrets + migrations), ADR-008 (production
  IdP — WorkOS)

---

## 1. Context

The platform code stack (3F observability, 3G error standardization, 3H
secrets + migration substrate, 3K.1 identity claims + DISABLED enforcement,
plus all clinical chunks 4A–4E.2 + the read-pagination substrate) is shipped
to `main`. The runtime guards are in place: `SecretValidator` aborts boot if
required secrets are missing, `ProdProfileOidcGuard` refuses to start the
`prod` profile against a mock issuer, `medcore_app` is the runtime DB role
(no superuser at runtime), and the Flyway migrator is a separate role with
its own credential.

What does **not** exist yet: any AWS infrastructure. The repo's
`infra/terraform/` directory is empty (`.gitkeep` only). The CI pipeline
runs five gates (governance, secret-scan, web, test, e2e) on every commit
but has no deploy step. There is no real environment outside the developer
laptop.

Without provisioned infrastructure:
- Nothing the platform protects can run for a real user.
- WorkOS integration (3K.2) cannot be tested end-to-end against a real
  issuer.
- Reference data (3M) cannot be loaded into a target database.
- The customer onboarding sequence (Phase 6D) is not even a hypothetical.

This ADR locks the dev-environment AWS baseline: shape, provisioning tool,
state-management pattern, cost ceiling, and the operating model between
the engineer (writes IaC, validates locally) and the operator (applies to
AWS). It is the prerequisite ADR for every subsequent infra slice.

## 2. Decision

> **We will provision the Medcore `dev` environment on a fresh single AWS
> account using HashiCorp Terraform (HCL), with a Terraform-managed S3 +
> DynamoDB state backend bootstrapped via `infra/bootstrap/` and a
> domain-split `infra/terraform/{network,data,identity,compute,app}/`
> module layout for everything that follows.**

Specific decisions this entails:

### 2.1 Account, runtime, and shape
- **Account:** Single fresh AWS account, HIPAA BAA signed before any
  workload runs. AWS Organizations is rejected as overkill at this stage;
  it is a future-slice consideration when prod + staging exist.
- **Compute:** ECS Fargate (HIPAA-eligible, no node management, native fit
  for the Spring Boot container). EKS, EC2, and Lambda are rejected (see
  §3).
- **Database:** Amazon RDS for PostgreSQL 16, `db.t4g.micro`, encrypted at
  rest (`KMS` aws-managed), 7-day automated backups, single-AZ in `dev`.
- **Network:** Public + private subnets across **two** AZs (RDS
  multi-subnet-group requirement), single NAT Gateway in one AZ for `dev`
  (≈$32/mo), upgrade to multi-AZ NAT for prod. RDS lives in private
  subnets; ECS tasks live in private subnets; ALB lives in public.
- **Identity:** GitHub Actions OIDC trust to AWS (no long-lived access
  keys committed anywhere). Per-environment IAM roles assumed via OIDC
  by the CI deploy job.
- **Container registry:** Amazon ECR (private repo).
- **Secrets:** AWS Secrets Manager. Slots into the existing
  `AwsSecretsManagerSecretSource` stub (Phase 3H) by replacing
  `NotImplementedError` with a real AWS SDK call.
- **Logging:** CloudWatch Logs (subscription log group per ECS service).
  An OTel collector deployment is out of scope for this ADR; ECS task
  emits ECS-formatted JSON to stdout, CloudWatch ingests, queries possible
  via Logs Insights.
- **TLS / domain:** No custom domain in `dev`. ALB DNS endpoint is the
  reachability surface. Custom domain + ACM cert deferred to a future
  prod-baseline ADR.

### 2.2 Provisioning tool: Terraform (HCL)
- **HCL syntax pinned via `terraform-version` 1.6+.** OpenTofu (the
  Linux-Foundation-stewarded fork after HashiCorp's BUSL-1.1 license
  change) is a drop-in replacement for the same HCL — operators may use
  either. The HCL we write here is portable across both.
- **AWS provider pinned to `~> 5.0`.** Major-version bumps require a new
  ADR or explicit revision of this one.
- **No CDK / Pulumi / CloudFormation.** A second IaC tool would split
  knowledge and tooling without proportional benefit at this stage.

### 2.3 State backend
- **S3 + DynamoDB lock**, the canonical pattern.
- **Bootstrap chicken-and-egg solved** by `infra/bootstrap/`: a separate
  Terraform project that uses LOCAL state, applied **once** by an
  operator, that creates the state bucket + lock table. From that point
  forward, every other Terraform project in the repo declares an `s3`
  backend pointing at the bootstrap-created bucket + table.
- **Bootstrap state** is committed to the bootstrap directory itself
  (`infra/bootstrap/terraform.tfstate`) and `.gitignore`d. Yes, this is
  asymmetric — it has to be, because nothing exists to back it up the
  first time. The bootstrap module is small, idempotent, and creates
  resources whose deletion would not lose ongoing data.
- **State bucket:** `medcore-dev-tfstate-<account-id>` (account-id
  suffix to make name globally unique without coupling to brand).
  Versioned, encrypted (SSE-S3), public-access blocked, lifecycle to
  expire non-current versions after 90 days.
- **Lock table:** `medcore-dev-tflock`, on-demand billing, hash key
  `LockID`.

### 2.4 Module layout
```
infra/
├── bootstrap/                     # Chunk A: state backend (local state)
│   ├── main.tf                    # S3 + DDB
│   ├── variables.tf
│   ├── outputs.tf
│   ├── versions.tf
│   ├── terraform.tfvars.example
│   └── README.md                  # operator instructions
└── terraform/
    └── envs/
        └── dev/
            ├── network/           # Chunk B: VPC, subnets, NAT, SGs
            ├── data/              # Chunk C: RDS + Secrets Manager
            ├── identity/          # Chunk D: IAM roles, GitHub OIDC
            ├── compute/           # Chunk E: ECR, ECS cluster, ALB
            └── app/               # Chunk F: medcore-api task + service
```

Each module declares its own S3 backend with a distinct `key` prefix
(e.g., `dev/network/terraform.tfstate`). State is **per-module**, not
monolithic, to keep blast radius small.

### 2.5 Operating model (engineer ↔ operator split)
- **Engineer (Claude / authoring contributor):** writes HCL, runs
  `terraform fmt -check`, `terraform validate`, `tflint`, `checkov`
  locally. Produces PRs containing IaC + ADR updates only.
- **Operator (account holder / human):** runs `terraform init`,
  `terraform plan`, reviews the plan output, runs `terraform apply`.
  Pastes the plan summary back into the PR for record. Holds the AWS
  credentials.
- **No one** uses the AWS console for provisioning. Console is read-only
  for inspection; every resource must be declared in HCL. Drift is
  caught by `terraform plan` showing a non-empty diff after a console
  change, which is treated as a build break.

### 2.6 Cost ceiling
- **Target:** ≤ **$80 / month** for `dev`.
- **Hard ceiling:** **$120 / month**. A change that would push past this
  requires a new ADR or amendment with explicit justification.
- **AWS Budgets alarm** at $90 (warning) and $115 (action — emails the
  account owner). Provisioned in Chunk D (identity) alongside the IAM
  setup since `aws_budgets_budget` lives in the management account.
- **Cost levers:** single NAT (vs multi-AZ), `db.t4g.micro` (vs
  `db.t4g.small`), Fargate Spot for non-critical workloads, no
  CloudFront in dev, no NAT for prod-only services.

### 2.7 No PHI in `dev`
- The `dev` environment uses synthetic data only (Synthea, future
  Phase 3M). No real customer PHI is ever written to the `dev` RDS.
  This is enforced operationally (no PHI imports allowed) and
  contractually (the BAA, while signed, is for production).
- A future ADR will define `staging` (BAA-covered, PHI-equivalent
  load-test data) and `prod` (real PHI, full controls).

## 3. Alternatives Considered

### 3.1 EKS instead of ECS Fargate
- **What it is:** Managed Kubernetes on AWS.
- **Why rejected:** EKS adds a control-plane bill (~$73/mo per cluster),
  node-management overhead, and CNI complexity. The Spring Boot
  container has no Kubernetes-specific dependencies — Fargate runs the
  same image with less operational surface. EKS is reconsidered when we
  have multiple deployable services and mature platform engineering
  capacity.

### 3.2 EC2 instead of ECS Fargate
- **What it is:** Manage VMs directly via Auto Scaling Groups.
- **Why rejected:** Patch management, AMI builds, scaling policies — all
  work that Fargate does for us. Cost difference at this scale is
  negligible. EC2 is a step backward.

### 3.3 AWS Lambda + SnapStart
- **What it is:** Function-as-a-service with JVM warm-start optimisation.
- **Why rejected:** Spring Boot's startup time, container size, and
  long-lived JDBC pooling are poor fits for Lambda. SnapStart helps but
  does not eliminate the impedance mismatch. Lambda is right for
  low-traffic stateless event handlers; the Medcore API is neither.

### 3.4 Aurora Postgres-compatible instead of RDS Postgres
- **What it is:** AWS's distributed Postgres-compatible engine.
- **Why rejected (for `dev`):** Aurora has no `db.t4g.micro` equivalent;
  the cheapest Aurora Serverless v2 ACU configuration is materially
  more expensive than a `db.t4g.micro` RDS instance, with no
  operational benefit at dev traffic. Reconsidered for prod when
  multi-AZ failover and read replicas justify the cost.

### 3.5 OpenTofu vs Terraform
- **What it is:** OpenTofu is the Linux-Foundation fork of Terraform
  after HashiCorp's BUSL-1.1 license change.
- **Why parked, not rejected:** The HCL we write is identical between
  the two. The decision of which CLI to use at apply time is operator
  choice, not source-tree choice. The repo does not pin to either; the
  operator runs whichever is in their PATH. This ADR may be revisited
  if a future feature is exclusive to one tool.

### 3.6 CDK / Pulumi (programmatic IaC)
- **What it is:** Infrastructure declared in TypeScript/Python/Go that
  compiles to CloudFormation (CDK) or directly applies (Pulumi).
- **Why rejected:** HCL's declarative posture forces clarity that
  imperative TS/Python encourages skipping. The team has Kotlin + TS
  expertise; mixing imperative IaC into the stack creates a third
  language for infra without compounding leverage. HCL is also the
  default in AWS-vendor docs and the broader ecosystem — searching for
  patterns is easier.

### 3.7 Terraform Cloud (TFC) for state + runs
- **What it is:** HashiCorp-hosted Terraform with remote state, plan
  preview in PR, and policy-as-code (Sentinel).
- **Why rejected for now:** TFC's free tier is limited and the paid
  tiers add per-engineer cost. S3 + DynamoDB matches TFC's state
  capability at near-zero cost, and Sentinel is replaced by `checkov`
  + `tflint` running in CI. Reconsidered when team size + audit posture
  justify the cost.

## 4. Consequences

### 4.1 What this enables
- Every subsequent infra chunk has a stable place to land (`infra/...`,
  state in S3, lock in DDB, conventions documented here).
- The runtime guards already in code (`SecretValidator`,
  `ProdProfileOidcGuard`, runtime role separation) gain something to
  protect.
- The `AwsSecretsManagerSecretSource` stub becomes a real
  implementation in Chunk C.
- WorkOS (3K.2) becomes integrable end-to-end in a future slice once the
  AWS environment exists to host the issuer config + integration tests.
- CD to AWS becomes possible in Chunk F (currently CI-only).

### 4.2 What this does not address
- No production environment. `prod` is a separate ADR + slice, gated on
  Phase B compliance operationalization (written policies, BAA inventory,
  rehearsed DR, access reviews).
- No staging. `staging` is between `dev` and `prod`; introduced when
  customer-facing workflows exist.
- No multi-region. Single region (`us-east-1`, default; configurable per
  module) until business need justifies.
- No backup-restore drill schedule. Tracked as a Phase B
  operationalization item, not blocked by this ADR.

### 4.3 Operational discipline this codifies
- **No console-driven changes.** Drift = build break.
- **No manual IAM role creation.** Every role in HCL.
- **No long-lived AWS access keys.** OIDC only.
- **No PHI in `dev`.** Synthetic data only, enforced by policy.
- **Module-per-domain state separation.** Network state ≠ data state ≠
  identity state. Smaller blast radius per change.
- **Cost ceiling enforced by AWS Budgets** alarm at $90 / $115.
- **Engineer ↔ operator split.** Engineer writes IaC; operator runs
  apply. Every apply produces a plan summary committed to the PR.

## 5. Implementation chunks

| Chunk | Scope | DoD |
|---|---|---|
| **A** (this ADR + `infra/bootstrap/`) | Terraform bootstrap: S3 state bucket + DynamoDB lock table | One-time `terraform apply` succeeds; bucket + table exist; downstream chunks can declare `s3` backend |
| **B** | `infra/terraform/envs/dev/network/` — VPC, public + private subnets across 2 AZs, single NAT, route tables, security-group skeletons | `terraform plan` clean; VPC reachable from a test EC2 in private subnet |
| **C** | `infra/terraform/envs/dev/data/` — RDS Postgres 16 (`db.t4g.micro`), KMS-encrypted, 7-day backups; Secrets Manager secrets for `medcore_app` and `medcore_migrator` Postgres roles; rotation hook stub | RDS reachable from private subnet; secrets retrievable by an IAM principal |
| **D** | `infra/terraform/envs/dev/identity/` — GitHub OIDC trust, ECS task role, Flyway migrator role, deploy role, AWS Budgets alarm at $90/$115 | GitHub Actions can `sts:AssumeRole`; budget alarm wired |
| **E** | `infra/terraform/envs/dev/compute/` — ECR repo, ECS cluster, Fargate task definitions (api + flyway-migrator), ALB, target group, CloudWatch log groups | A bare nginx Fargate task deploys end-to-end through CD |
| **F** | `infra/terraform/envs/dev/app/` + Dockerfile + CI deploy job — `medcore-api` container deploys to ECS, reads secrets from Secrets Manager, ALB routes to `/actuator/health/readiness` | `git push origin main` deploys to dev; ALB DNS returns 200 on readiness probe |
| **G** | Smoke tests, runbook (`docs/runbooks/aws-dev-baseline.md`), ADR-010 status finalization, governance, commit + push + merge | Documented; rollback path verified; `dev` env operational |

Each chunk is independently shippable with its own PR. State is
per-module, so a chunk's apply never touches another chunk's resources.

## 6. References

- ADR-006 — production secrets + migration architecture (the design this
  ADR operationalizes for AWS)
- ADR-008 — production IdP (WorkOS), unblocked once dev env exists
- HashiCorp Terraform docs (HCL 1.6+, AWS provider 5.x)
- AWS HIPAA-eligible services list
- `apps/api/src/main/kotlin/com/medcore/platform/persistence/SecretSource.kt`
  — abstraction this ADR's Chunk C wires to AWS
