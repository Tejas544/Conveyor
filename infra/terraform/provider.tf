# The three `skip_*` flags are what let `terraform plan` run against dummy credentials with no
# real AWS account reachable at all (see README.md) — they suppress the provider's own STS
# credential-validation and account-ID-lookup calls at initialization, which would otherwise fail
# (or hang) before a single resource is even considered. This is standard practice for CI-only
# `validate`/`plan` gates on IaC that is deliberately never applied.
provider "aws" {
  region = var.aws_region

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_region_validation      = true
}
