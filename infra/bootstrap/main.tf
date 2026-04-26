# =============================================================================
# Terraform bootstrap — state backend (S3 + DynamoDB lock)
# =============================================================================
#
# Applied ONCE, MANUALLY, with LOCAL state (no `backend "s3"` block here —
# we are creating the very bucket that other modules will use). After this
# applies successfully, every other Terraform project in the repo declares
# an `s3` backend pointing at the bucket + table this module creates.
#
# See ADR-010 §2.3 for the full rationale and the chicken-and-egg pattern.
#
# Idempotent: re-applying produces a no-op plan once the resources exist.
# Resources are intentionally simple — the S3 bucket holds Terraform state
# only, never PHI or application data. State files are encrypted at rest;
# bucket-level public access is fully blocked.

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.tags
  }
}

data "aws_caller_identity" "current" {}

locals {
  # Globally-unique S3 bucket name. Suffixing with the account ID lets
  # the same name_prefix be used across accounts without collision and
  # makes the bucket trivially identifiable as "this account's state".
  state_bucket_name = "${var.name_prefix}-tfstate-${data.aws_caller_identity.current.account_id}"

  # Lock table doesn't need account-id suffix — DynamoDB names are
  # account-scoped, not global.
  lock_table_name = "${var.name_prefix}-tflock"
}

# -----------------------------------------------------------------------------
# Terraform state bucket
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket_name

  # `force_destroy = false` is the safe default. Bucket destruction
  # requires manually emptying it first — guards against `terraform
  # destroy` accidentally nuking state history.
  force_destroy = false
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  # NORMATIVE: encryption is explicitly declared, NOT relied on as an
  # AWS default. Although SSE-S3 has been the AWS default since
  # 2023-01, ADR-010 §2.5 forbids relying on implicit cloud defaults
  # for security-critical posture. A console operator who disables
  # the bucket's default encryption would silently regress unless
  # this resource exists in HCL — `terraform plan` then surfaces the
  # drift loudly.
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      # SSE-S3 (AES-256, AWS-managed keys). Sufficient for state files
      # which contain resource attributes (some sensitive — DB passwords
      # if present in state are encrypted at rest by SSE-S3 itself).
      # Customer-managed KMS upgrade is a future-slice consideration.
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  # All four flags ON. State is never public, ever.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  # Rule: expire non-current versions after N days. Current versions are
  # kept indefinitely (state history); the rule trims old revisions to
  # bound storage cost. ADR-010 §2.3 documents the 90-day default.
  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    # Empty filter applies the rule to every object. Required by the
    # AWS provider's v5 schema even when no prefix/tag scoping exists.
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }

    # Multipart-upload cleanup — orphaned MPU parts older than 7 days
    # are aborted and reclaimed. Cheap hygiene; prevents silent cost
    # accumulation if a future apply is interrupted.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# -----------------------------------------------------------------------------
# DynamoDB lock table
# -----------------------------------------------------------------------------

resource "aws_dynamodb_table" "lock" {
  name = local.lock_table_name

  # Pay-per-request — for state-locking traffic this is materially cheaper
  # than provisioned throughput. Locks are acquired once per `terraform
  # apply`, held briefly, and released. Daily write count is dozens, not
  # thousands.
  billing_mode = "PAY_PER_REQUEST"

  hash_key = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  # Point-in-time recovery is cheap and saves the operator's day if the
  # table is accidentally truncated.
  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}
