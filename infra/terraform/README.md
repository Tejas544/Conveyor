# `infra/terraform` — the AWS fallback path (ARCHITECTURE.md §15.4)

This is the costed, real-AWS deployment (VPC, EKS, RDS, ECR, IAM/IRSA) kept on record per ADR-13:
written in full, `validate`d and `plan`ned on every push (`.github/workflows/build.yml`'s
`terraform-plan` job), **never `terraform apply`'d by anything automated**
(`verify-no-terraform-apply` greps every workflow file to prove it).

## Why `terraform plan` runs with no AWS account at all

- `provider.tf` sets `skip_credentials_validation`, `skip_requesting_account_id`, and
  `skip_region_validation` — this stops the AWS provider from making an STS call just to start up.
- Every input that would otherwise need a live API read (`data "aws_availability_zones"`,
  `data "aws_caller_identity"`, an AMI lookup) is avoided by construction: AZs are a hardcoded
  variable (`variables.tf`), the EKS node group resolves its own AMI via `ami_type` rather than a
  Terraform-side lookup, and no IAM policy here depends on the caller's account ID.
- With no prior state (this configuration has never been applied), `terraform plan` has nothing to
  refresh — every resource is "to be created," computed entirely from the config and the provider's
  bundled schema. The one `data` source that *would* need a live read
  (`data "tls_certificate" "eks_oidc"` in `eks.tf`, fetching the EKS OIDC issuer's cert for IRSA) is
  reading a value that doesn't exist yet either (`aws_eks_cluster.this` isn't created), so Terraform
  defers it to apply time automatically and it never executes during `plan`.

CI runs `terraform init -backend=false` (no remote state to authenticate to) then `plan` with
`AWS_ACCESS_KEY_ID=dummy-access-key-id` / `AWS_SECRET_ACCESS_KEY=dummy-secret-access-key` — literally
made-up values, sufficient because nothing above ever asks AWS to confirm they're real.

## If this is ever actually applied

Per `ARCHITECTURE.md` §15.4 and `CLAUDE.md` §9, that requires the human's explicit, separate
go-ahead — this configuration existing and passing CI is not that go-ahead. If it is given:

1. Real credentials, not the CI dummy ones (`aws configure` or environment variables).
2. `TF_VAR_db_password` set to a real generated secret — never the committed placeholder default in
   `variables.tf`.
3. `terraform init` (a real backend is worth adding at that point — none is configured here since
   this has never had real state to protect).
4. `terraform apply`.
5. Fetch the AWS Load Balancer Controller's current IAM policy from its own published release docs
   and attach it in place of `iam-irsa.tf`'s deliberately inert placeholder policy.
6. Tear down with `terraform destroy` once the demo window is over — `infra/teardown.sh` documents
   this step alongside the local-hygiene commands it also runs.
