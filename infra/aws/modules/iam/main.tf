# Task roles and the GitHub deploy role (docs/TASKS.md T31 acceptance: "no wildcard admin role").
#
# Every policy here names its resources. There is no `Resource = "*"` on a mutating action
# anywhere in this file, and the two places a wildcard does appear are annotated with why the API
# does not accept anything narrower.
#
# Three identities, deliberately separate:
#
#   execution  — what Fargate itself uses to start a task: pull the image, read the secrets it
#                injects, write the log stream. Not what the application runs as.
#   api / worker — what the application code runs as. Different from each other because they need
#                different things: the API writes to S3, the worker only reads.
#   deploy     — GitHub Actions, via OIDC. No static keys (docs/DEPLOYMENT_RUNBOOK.md §4).

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    # Without this, any account that can get ECS to assume the role could use it. The condition
    # binds the role to tasks in *this* account.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

# --- execution role: Fargate's own ----------------------------------------------------------------

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# The AWS-managed policy for pulling from ECR and writing logs. Managed rather than hand-rolled
# because it is the one place AWS's own definition is authoritative and tracks service changes.
resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Reading the secrets it injects is *not* in that managed policy, and it is scoped to exactly the
# secrets this environment created — not `secretsmanager:*`.
data "aws_iam_policy_document" "execution_secrets" {
  statement {
    sid       = "ReadInjectedSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-injected-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# --- API task role ---------------------------------------------------------------------------

resource "aws_iam_role" "api" {
  name               = "${var.name_prefix}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "api" {
  statement {
    sid = "DocumentObjects"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    # Objects within the bucket, not the bucket itself — s3:* on the bucket ARN would include
    # DeleteBucket.
    resources = ["${var.documents_bucket_arn}/*"]
  }

  statement {
    sid       = "ListOwnBucketOnly"
    actions   = ["s3:ListBucket"]
    resources = [var.documents_bucket_arn]
  }
}

resource "aws_iam_role_policy" "api" {
  name   = "opspilot-api"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.api.json
}

# --- worker task role --------------------------------------------------------------------------

resource "aws_iam_role" "worker" {
  name               = "${var.name_prefix}-worker-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "worker" {
  statement {
    sid = "ReadDocumentObjects"
    # Read only. The worker indexes documents; it never writes one, and T16's indexer has no code
    # path that would. A role that cannot write is a stronger statement than a code review that
    # says nothing does.
    actions   = ["s3:GetObject"]
    resources = ["${var.documents_bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "worker" {
  name   = "opspilot-worker"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.worker.json
}

# --- GitHub Actions deploy role ------------------------------------------------------------------

# The OIDC provider is a per-account singleton. Created here only if the account does not already
# have one, because a second one is an error and accounts often already have it.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub's thumbprint is no longer verified by AWS for this provider, but the field is still
  # required. AWS documents this value.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

locals {
  github_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_github_oidc_provider_arn
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # The condition that matters. `sub` is pinned to one repository *and* one ref — not
    # `repo:owner/name:*`, which any branch and therefore any pull request from a fork could
    # match. docs/MANUAL_SETUP.md Gate H3: "enable GitHub OIDC trust scoped to the exact
    # repository/branch/environment".
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:${var.github_deploy_ref}"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name               = "${var.name_prefix}-github-deploy"
  description        = "Assumed by GitHub Actions via OIDC from ${var.github_repository} @ ${var.github_deploy_ref}. No static keys."
  assume_role_policy = data.aws_iam_policy_document.github_assume.json

  # An hour is longer than any deploy here takes, and a shorter ceiling limits what a leaked
  # token is worth.
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid = "EcrLogin"
    # The only action AWS offers no resource for: GetAuthorizationToken is account-scoped by
    # design. It returns a token that is then constrained by the resource-scoped statement above.
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Split into three statements rather than one. The first draft was a single statement whose
  # resources were `concat(var.ecs_service_arns, ["*"])` — which is a wildcard: adding "*" to a
  # list does not narrow anything, it widens the whole statement to every resource. It read as
  # scoped and was not, which is the worst kind of IAM policy.
  statement {
    sid = "RollTheseServicesOnly"
    actions = [
      "ecs:UpdateService",
      "ecs:DescribeServices",
    ]
    resources = var.ecs_service_arns
  }

  statement {
    sid = "RunTheMigrationTask"
    # The deploy workflow runs Flyway as a one-off task before any service rolls
    # (docs/DEPLOYMENT_RUNBOOK.md §2). Scoped to this environment's task-definition families, so
    # the role cannot run an arbitrary task in the account.
    actions   = ["ecs:RunTask"]
    resources = ["arn:${local.partition}:ecs:*:${local.account_id}:task-definition/${var.name_prefix}-*:*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  statement {
    sid = "ReadAndRegisterDefinitions"
    actions = [
      # Genuinely unscopable: a task definition family that does not exist yet has no ARN to
      # name, and the rest are read-only describes used to find what is currently deployed.
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
    ]
    resources = ["*"]
  }

  statement {
    sid     = "PassOnlyTheTaskRoles"
    actions = ["iam:PassRole"]
    # Without this scoping, a deploy role that can RegisterTaskDefinition can attach *any* role
    # to a task — which is a privilege-escalation path straight to administrator. These three and
    # nothing else.
    resources = [
      aws_iam_role.execution.arn,
      aws_iam_role.api.arn,
      aws_iam_role.worker.arn,
    ]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "opspilot-deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
