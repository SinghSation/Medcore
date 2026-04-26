# Pinned tool + provider versions for the Terraform bootstrap project.
#
# This is the ONLY module in the repo that uses LOCAL state (its job is
# to create the S3 bucket + DynamoDB lock table that all downstream
# modules use as a remote backend). See ADR-010 §2.3 for the chicken-
# and-egg rationale.
#
# OpenTofu (>= 1.6) is a drop-in replacement and works against the same
# HCL with no changes (ADR-010 §3.5).

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}
