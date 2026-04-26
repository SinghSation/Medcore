# Inputs to the Terraform bootstrap project.
#
# Defaults match ADR-010 §2.1. Override via `terraform.tfvars` if the
# operator's account is in a non-default region or wants a custom
# resource-name prefix.

variable "aws_region" {
  description = "AWS region where the state bucket + lock table live. State is single-region; downstream modules can deploy to other regions but their state still resolves here."
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix for all bootstrap-created resource names (e.g., \"medcore-dev\"). Combined with the AWS account ID for the bucket name to satisfy S3's global-unique requirement."
  type        = string
  default     = "medcore-dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.name_prefix))
    error_message = "name_prefix must be 3–31 chars, lowercase letters / digits / hyphens, starting with a letter (S3-bucket-name compatible)."
  }
}

variable "tags" {
  description = "Tags applied to every bootstrap-created resource. AWS cost-allocation reporting relies on these."
  type        = map(string)
  default = {
    "medcore:env"        = "dev"
    "medcore:component"  = "tf-bootstrap"
    "medcore:managed-by" = "terraform"
    "medcore:adr"        = "ADR-010"
  }
}

variable "noncurrent_version_expiration_days" {
  description = "After this many days, non-current versions of state objects are deleted from the state bucket. Default 90 — tradeoff between recovery window and storage cost."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_expiration_days >= 30
    error_message = "noncurrent_version_expiration_days must be >= 30 to retain a meaningful recovery window."
  }
}
