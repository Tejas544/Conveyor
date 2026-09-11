variable "aws_region" {
  description = "AWS region for the fallback deployment (ARCHITECTURE.md §15.4)."
  type        = string
  default     = "us-east-1"
}

# Hardcoded, not looked up via `data "aws_availability_zones"` — that data source makes a live
# EC2 API call even during `plan`, which is exactly what this configuration is built to avoid
# needing (see README.md). Two AZs is the minimum for an EKS-eligible VPC and RDS's own multi-AZ
# subnet group requirement.
variable "availability_zones" {
  description = "Fixed AZ pair for us-east-1 — avoids a live data-source lookup during plan."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "cluster_name" {
  description = "EKS cluster name."
  type        = string
  default     = "conveyor"
}

variable "kubernetes_version" {
  description = "EKS control-plane Kubernetes version."
  type        = string
  default     = "1.31"
}

variable "node_instance_type" {
  description = "Worker node instance type — ARCHITECTURE.md §15.4's costed line item."
  type        = string
  default     = "t3.medium"
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "db_instance_class" {
  description = "RDS Postgres instance class — ARCHITECTURE.md §15.4's costed line item."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_name" {
  type    = string
  default = "conveyor"
}

variable "db_username" {
  type    = string
  default = "conveyor_admin"
}

# A placeholder, not a real secret (CLAUDE.md §2.11) — the same "local dev only"-style literal
# already committed throughout this repo (values.yaml, docker-compose.yml, .env.example) for
# infrastructure that is actually deployed. This one never is: `terraform apply` never runs
# automatically (ADR-13, verify-no-terraform-apply's own CI gate), so there is no live RDS instance
# this value could ever protect. A human running a real, explicitly-authorized apply of this
# fallback path (ARCHITECTURE.md §15.4) overrides it with `TF_VAR_db_password` from their own shell,
# never by editing this default.
variable "db_password" {
  description = "RDS master password. Override via TF_VAR_db_password before any real apply."
  type        = string
  sensitive   = true
  default     = "changeme_before_any_real_apply"
}

variable "ecr_repository_names" {
  description = "One ECR repository per deployable image — mirrors the six GHCR images the $0 path already pushes."
  type        = list(string)
  default = [
    "order-service",
    "inventory-service",
    "payment-service",
    "saga-orchestrator",
    "dispatch-service",
    "conveyor-verifier",
  ]
}

variable "ecr_image_retention_count" {
  description = "ARCHITECTURE.md §15.4's own line: 'lifecycle policy keeps 10 images'."
  type        = number
  default     = 10
}
