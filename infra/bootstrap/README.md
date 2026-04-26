# Terraform bootstrap

Creates the Terraform state backend (S3 bucket + DynamoDB lock table) that
every other Terraform project in `infra/terraform/` uses. **Apply once,
manually, then leave alone.** See [ADR-010 §2.3](../../docs/adr/010-aws-dev-baseline.md)
for the chicken-and-egg rationale.

## Why this exists separately

Every other module declares an `s3` Terraform backend. The bucket has to
exist before any of them can run their first `terraform init`. So this
module:

- Uses **local state** (the `terraform.tfstate` file lives in this
  directory and is gitignored).
- Is the **only module the operator runs by hand**, before anything else.
- Is **idempotent**: re-applying after the first run produces a no-op
  plan.

After this applies successfully, downstream modules paste the bucket /
table names into their own `backend "s3"` blocks and use the standard
remote-state pattern.

## Prerequisites

1. **AWS account** with the HIPAA BAA signed (per ADR-010 §2.1).
2. **AWS credentials** for an IAM principal with permissions to create
   S3 buckets, DynamoDB tables, and read account identity. For
   first-time bootstrap this is typically the account root or an
   admin-equivalent IAM user; subsequent infra chunks use a narrower
   GitHub OIDC role provisioned in Chunk D.
3. **Terraform 1.6+** or **OpenTofu 1.6+** in `PATH`. Either works.
   - macOS: `brew install terraform` or `brew install opentofu`
   - Verify: `terraform version` (or `tofu version`)

## How to apply (first run, ~3 minutes)

```bash
cd infra/bootstrap

# 1. Authenticate to AWS (any standard mechanism works — env vars,
#    `aws configure`, AWS SSO, etc.). Sanity-check by running:
aws sts get-caller-identity
# Confirm the Account number matches the intended dev account.

# 2. Optional: copy the example tfvars and edit if you need non-default
#    region or prefix.
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars

# 3. Initialize (downloads the AWS provider; creates local state file).
terraform init

# 4. Plan. Review carefully — this is an authoritative apply with no
#    state to fall back on if something is wrong.
terraform plan -out=bootstrap.tfplan

# Expected resources:
#   + aws_s3_bucket.state                                       (1 resource)
#   + aws_s3_bucket_versioning.state                            (1 resource)
#   + aws_s3_bucket_server_side_encryption_configuration.state  (1 resource)
#   + aws_s3_bucket_public_access_block.state                   (1 resource)
#   + aws_s3_bucket_lifecycle_configuration.state               (1 resource)
#   + aws_dynamodb_table.lock                                   (1 resource)
#
# Total: 6 resources to add, 0 to change, 0 to destroy.

# 5. Apply.
terraform apply bootstrap.tfplan

# 6. Capture the outputs — every downstream module needs these.
terraform output
```

**Save the outputs** to the PR as evidence of the apply:

```bash
terraform output -json | tee bootstrap-outputs.json
# Then paste a redacted summary into the PR (account_id is fine to share
# privately; do not commit the JSON file to git).
```

## What lands in AWS

| Resource | Name pattern | Purpose |
|---|---|---|
| S3 bucket | `medcore-dev-tfstate-<account-id>` | Terraform state, versioned, SSE-S3, public-access blocked, 90-day lifecycle on non-current versions |
| DynamoDB table | `medcore-dev-tflock` | State-lock coordination, on-demand billing, PITR enabled, encrypted |

**Cost:** approximately **$0–2 / month**, dominated by S3 storage of state
(typically <50 MB total) and DynamoDB on-demand reads (one read per
`terraform apply`).

## What this does NOT create

- No VPC, no compute, no database — those land in Chunks B–E. Bootstrap
  only creates state plumbing.
- No IAM roles, GitHub OIDC trust, or budget alarm — those are Chunk D.
- No production-environment resources. This is `dev` only. A future ADR
  will codify staging + prod baselines.

## Routine operations

### Re-running

`terraform plan` produces no changes once the resources exist. Safe to
re-run any time as a sanity check.

### Updating the lifecycle policy

Edit `noncurrent_version_expiration_days` in `terraform.tfvars` and
`terraform apply`. Existing non-current versions older than the new
threshold are reaped on the next S3 lifecycle sweep (typically within
24 hours).

### Drift detection

Run `terraform plan` periodically. A non-empty diff means someone
modified a bootstrap resource via the AWS console — investigate
immediately. Per ADR-010 §2.5 the console is read-only.

## Destroying (rare)

`terraform destroy` only works after the operator has (1) manually
emptied the S3 bucket and (2) flipped `deletion_protection_enabled` on
the lock table to `false`. Both guards are intentional — the bucket has
`force_destroy = false`, and the table is shielded by a deletion-
protection flag, mirroring the bucket's posture. Procedure:

```bash
# 1. Empty the bucket (every version of every object).
aws s3api list-object-versions \
  --bucket "$(terraform output -raw state_bucket_name)" \
  --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' \
  --output json \
  | aws s3api delete-objects \
      --bucket "$(terraform output -raw state_bucket_name)" \
      --delete file:///dev/stdin

# 2. Repeat for delete markers.
aws s3api list-object-versions \
  --bucket "$(terraform output -raw state_bucket_name)" \
  --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' \
  --output json \
  | aws s3api delete-objects \
      --bucket "$(terraform output -raw state_bucket_name)" \
      --delete file:///dev/stdin

# 3. Disable lock-table deletion protection. Edit `main.tf` to set
#    `deletion_protection_enabled = false` on `aws_dynamodb_table.lock`,
#    then apply ONLY that change:
$EDITOR main.tf
terraform apply -target=aws_dynamodb_table.lock

# 4. Now destroy.
terraform destroy
```

This procedure exists for completeness; in practice the bootstrap is
never destroyed for the lifetime of the `dev` environment.

## State file safety

`terraform.tfstate` and `terraform.tfstate.backup` in this directory are
**gitignored** — they may contain resource ARNs and account IDs that
should not be in the repo. They are also small enough to back up by
hand if needed (~5 KB) — copy to a secure location after the first
apply if you want belt-and-suspenders recovery.

The repo's other Terraform modules use **remote state** (this bucket)
and so do not have this concern.

## ⚠️ DO NOT disable bucket versioning

The state bucket has S3 versioning **enabled** by Terraform (see
`aws_s3_bucket_versioning.state` in `main.tf`). This is **not optional**:

- Versioning is the only mechanism that lets the operator recover from
  a corrupt or accidentally-truncated state file. Without it, a single
  `terraform apply` that goes wrong can render every downstream module
  unrecoverable.
- The DynamoDB lock prevents concurrent writes but does **not** protect
  against logical corruption of the state file itself.
- The 90-day non-current-version lifecycle means corrupt revisions
  eventually age out, but recent history is always recoverable.

**Operational rules:**
1. **Never disable versioning** via the AWS console. ADR-010 §2.5
   forbids console-driven changes; if someone does it anyway,
   `terraform plan` will surface the drift on the next run and the
   build is treated as broken until the version setting is restored.
2. **Never delete state-bucket objects manually** unless following the
   destroy procedure in this README. State history is the recovery
   ledger; pruning it bypasses the lifecycle rule and can leave
   downstream modules unable to refresh.
3. **If versioning is somehow turned off**, run `terraform apply` from
   this directory immediately — the `aws_s3_bucket_versioning`
   resource will re-enable it. Then audit the bucket's recent
   activity in CloudTrail to confirm no state was lost.
