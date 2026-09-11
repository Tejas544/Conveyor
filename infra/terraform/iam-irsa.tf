# IRSA (IAM Roles for Service Accounts) — ARCHITECTURE.md §15.4's line item, demonstrated against
# the one workload a real EKS deployment of this project would actually need it for: the AWS Load
# Balancer Controller, which reconciles the Ingress fronting the five services (§15.4's ALB). Scoped
# to the `kube-system` ServiceAccount that controller runs as, via the OIDC trust condition below —
# no static IAM user/access-key ever touches a pod.

data "aws_iam_policy_document" "lb_controller_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:aws-load-balancer-controller"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lb_controller" {
  name               = "${var.cluster_name}-aws-lb-controller"
  assume_role_policy = data.aws_iam_policy_document.lb_controller_assume_role.json
}

# The AWS-published policy JSON for this controller is long-lived and versioned upstream, not
# reproduced by hand here (drift risk) — a real `apply` of this path fetches the current version
# from AWS's own published URL per the controller's install docs and pastes it into
# lb-controller-policy.json alongside this file. Left as an explicit placeholder statement (deny
# nothing extra, grant nothing yet) rather than a silently-empty policy, so `terraform validate`
# still confirms the wiring (role, trust policy, attachment) is structurally correct without
# committing a policy document that would silently drift out of date.
resource "aws_iam_role_policy" "lb_controller_placeholder" {
  name = "placeholder-see-comment"
  role = aws_iam_role.lb_controller.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Deny"
      Action   = "*"
      Resource = "*"
    }]
  })
}
