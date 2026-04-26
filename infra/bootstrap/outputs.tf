# Outputs from the bootstrap project. Downstream Terraform modules paste
# these values into their own `backend "s3"` blocks (Terraform's backend
# configuration cannot use variable interpolation, so the values must be
# literal in each downstream module — these outputs serve as the
# canonical reference).

output "state_bucket_name" {
  description = "Name of the S3 bucket holding Terraform state for all downstream modules. Paste into each module's `backend \"s3\" { bucket = \"…\" }` block."
  value       = aws_s3_bucket.state.id
}

output "state_bucket_arn" {
  description = "ARN of the state bucket. Useful for IAM policies that need to grant the deploy role read/write access to its own state."
  value       = aws_s3_bucket.state.arn
}

output "lock_table_name" {
  description = "Name of the DynamoDB lock table. Paste into each module's `backend \"s3\" { dynamodb_table = \"…\" }` block."
  value       = aws_dynamodb_table.lock.id
}

output "lock_table_arn" {
  description = "ARN of the lock table. Useful for IAM policies that need to grant the deploy role lock-acquire/release on its own state."
  value       = aws_dynamodb_table.lock.arn
}

output "aws_region" {
  description = "Region the state bucket + lock table live in. Paste into each module's `backend \"s3\" { region = \"…\" }` block."
  value       = var.aws_region
}

output "aws_account_id" {
  description = "AWS account ID — useful for cross-checking the operator is applying against the intended account."
  value       = data.aws_caller_identity.current.account_id
}
