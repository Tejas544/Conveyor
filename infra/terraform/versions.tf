# PLAN.md Phase 14 / ADR-13: this whole directory is the costed AWS fallback path
# (ARCHITECTURE.md §15.4), written in full and kept CI-`validate`d/`plan`ned so it never rots, but
# never `terraform apply`'d by anything automated — see README.md for exactly how `plan` runs with
# no real AWS account at all, and .github/workflows/build.yml's `verify-no-terraform-apply` job for
# the proof that no workflow ever invokes `apply`.
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # No remote backend: this configuration is never applied, so there is no real state to protect
  # or share. CI runs `terraform init -backend=false` for exactly this reason. A local backend file
  # (terraform.tfstate) is gitignored the same as any other Terraform project's, in case a human
  # ever does run `apply` deliberately against the fallback path (ARCHITECTURE.md §15.4's own
  # sign-off requirement).
}
