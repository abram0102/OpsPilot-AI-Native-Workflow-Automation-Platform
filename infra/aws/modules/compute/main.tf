# ECR, the ECS cluster, and the two Fargate services (docs/TASKS.md T31).
#
# The API is behind the load balancer; the worker is not and has no listener at all — it reaches
# Redis and the internal API outbound and nothing reaches it. That asymmetry is the deployment
# expression of docs/SECURITY_AND_TESTING.md §1's worker boundary.

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

locals {
  api_container    = "api"
  worker_container = "worker"
  web_container    = "web"
}

# --- registries -----------------------------------------------------------------------------

resource "aws_ecr_repository" "api" {
  name                 = "${var.name_prefix}/api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "web" {
  name                 = "${var.name_prefix}/web"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "worker" {
  name                 = "${var.name_prefix}/worker"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# IMMUTABLE above is the one worth explaining: it makes a tag mean one image forever. Without it,
# "the image we deployed" is a moving target and a rollback can silently roll forward.

# Untagged layers accumulate on every build. Ten tagged images is enough to roll back through.
resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "worker" {
  repository = aws_ecr_repository.worker.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "web" {
  repository = aws_ecr_repository.web.name
  policy     = local.ecr_lifecycle_policy
}

locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after a day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the last 10 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 10
        }
        action = { type = "expire" }
      },
    ]
  })
}

# --- cluster ----------------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = var.container_insights_enabled ? "enabled" : "disabled"
  }
}

# Fargate Spot is not used for either service. The API serves users, and the worker holds a lease
# on a run it is executing — a two-minute Spot interruption mid-run is recoverable (T25's lease
# expires and another worker reclaims it) but it is needless churn for a few dollars at this size.
resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# --- logs -------------------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "api" {
  name              = "/opspilot/${var.environment}/api"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/opspilot/${var.environment}/worker"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "web" {
  name              = "/opspilot/${var.environment}/web"
  retention_in_days = var.log_retention_days
}

# --- the tasks' shared security group ------------------------------------------------------------

resource "aws_security_group" "tasks" {
  name        = "${var.name_prefix}-tasks"
  description = "Application tasks. Inbound only from the load balancer; outbound anywhere."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-tasks" }
}

resource "aws_vpc_security_group_ingress_rule" "web_from_public_alb" {
  security_group_id = aws_security_group.tasks.id
  description       = "Web port from the public load balancer only"

  referenced_security_group_id = var.alb_security_group_id
  from_port                    = var.web_port
  to_port                      = var.web_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "api_from_internal_alb" {
  security_group_id = aws_security_group.tasks.id
  description       = "API port from the internal load balancer only — never from the public one"

  referenced_security_group_id = var.internal_alb_security_group_id
  from_port                    = var.api_port
  to_port                      = var.api_port
  ip_protocol                  = "tcp"
}

# Outbound is open: tasks reach RDS, ElastiCache, Secrets Manager, ECR, CloudWatch and — in real
# AI mode — a provider on the public internet. Restricting egress by CIDR would mean maintaining
# a list of AWS service ranges, which goes stale and fails in ways that look like bugs. The
# meaningful control is that *inbound* is one rule from one security group.
resource "aws_vpc_security_group_egress_rule" "tasks_out" {
  security_group_id = aws_security_group.tasks.id
  description       = "Outbound to AWS services and, in real AI mode, the configured provider"

  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "-1"
}

# --- task definitions ----------------------------------------------------------------------------

locals {
  # Everything non-secret. Note what is NOT here: no password, no token, no API key. Those arrive
  # through `secrets`, which ECS resolves from Secrets Manager at task start — so they never
  # appear in the task definition, in the console, or in a `describe-task-definition` output.
  common_environment = [
    { name = "SPRING_PROFILES_ACTIVE", value = "production" },
    { name = "AI_MODE", value = var.ai_mode },
    { name = "POSTGRES_HOST", value = var.db_endpoint },
    { name = "POSTGRES_PORT", value = tostring(var.db_port) },
    { name = "POSTGRES_DB", value = var.db_name },
    { name = "POSTGRES_USER", value = var.db_username },
    { name = "REDIS_HOST", value = var.redis_endpoint },
    { name = "REDIS_PORT", value = tostring(var.redis_port) },
    { name = "STORAGE_PROVIDER", value = "s3" },
    { name = "AWS_S3_BUCKET", value = var.documents_bucket_name },
    { name = "AWS_REGION", value = var.region },
    { name = "API_OIDC_ISSUER_URI", value = var.oidc_issuer_uri },
    { name = "API_OIDC_AUDIENCE", value = var.oidc_audience },
    # Belt and braces with ProductionSafetyGuard, which refuses to start if this is ever true
    # under the production profile. Set explicitly so the task definition states the intent.
    { name = "API_ALLOW_TEST_ISSUER", value = "false" },
  ]

  common_secrets = [
    { name = "POSTGRES_PASSWORD", valueFrom = "${var.database_secret_arn}:password::" },
    { name = "REDIS_PASSWORD", valueFrom = "${var.redis_secret_arn}:auth_token::" },
    { name = "INTERNAL_SERVICE_SIGNING_SECRET", valueFrom = var.internal_service_secret_arn },
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.name_prefix}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory_mb
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.api_task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    # Graviton. Cheaper per vCPU-hour than x86 at identical performance for a JVM, and the images
    # have to be built for it — docs/DEPLOYMENT_RUNBOOK.md and T32's build workflow say so.
    cpu_architecture = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = local.api_container
      image     = "${aws_ecr_repository.api.repository_url}:${var.api_image_tag}"
      essential = true

      portMappings = [{ containerPort = var.api_port, protocol = "tcp" }]

      environment = local.common_environment
      secrets     = local.common_secrets

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "api"
        }
      }

      # The container's own check, separate from the load balancer's. ECS replaces a task that
      # fails this even if the ALB has already stopped routing to it.
      healthCheck = {
        command  = ["CMD-SHELL", "curl -fsS http://localhost:${var.api_port}/actuator/health/liveness || exit 1"]
        interval = 30
        timeout  = 5
        retries  = 3
        # A JVM with Flyway migrations to run needs real time before the first check counts.
        startPeriod = 120
      }
    },
  ])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.name_prefix}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory_mb
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.worker_task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = local.worker_container
      image     = "${aws_ecr_repository.worker.repository_url}:${var.worker_image_tag}"
      essential = true

      # No portMappings. Nothing connects to the worker; it consumes a stream and calls the
      # internal API outbound.
      environment = concat(local.common_environment, [
        { name = "API_BASE_URL", value = var.internal_api_base_url },
      ])
      secrets = local.common_secrets

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.worker.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "worker"
        }
      }
    },
  ])
}

# --- services ---------------------------------------------------------------------------------

resource "aws_ecs_service" "api" {
  name            = "${var.name_prefix}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.api_target_group_arn
    container_name   = local.api_container
    container_port   = var.api_port
  }

  # Long enough for Flyway plus a JVM start. Too short and ECS kills a task that was going to
  # become healthy, repeatedly, which looks exactly like a crash loop.
  health_check_grace_period_seconds = 180

  deployment_circuit_breaker {
    enable = true
    # A deploy that cannot become healthy rolls itself back instead of leaving the service down
    # while someone notices.
    rollback = true
  }

  lifecycle {
    # The image tag is set by the deploy workflow (T32), not by this configuration. Without this,
    # every `tofu apply` would roll the service back to whatever tag is in the variables.
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [var.alb_listener_dependency]
}

resource "aws_ecs_service" "worker" {
  name            = "${var.name_prefix}-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}


# --- web ----------------------------------------------------------------------------------------
#
# The public face. It holds the Auth0 session (the browser never sees an access token) and calls
# the API over the internal load balancer, which never leaves the VPC.

resource "aws_ecs_task_definition" "web" {
  family                   = "${var.name_prefix}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.web_cpu
  memory                   = var.web_memory_mb
  execution_role_arn       = var.execution_role_arn
  # No task role: the web service calls the API over HTTP with the user's own token and touches
  # no AWS resource directly. A role with no policies would still be a role somebody could
  # attach a policy to later.
  task_role_arn = null

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = local.web_container
      image     = "${aws_ecr_repository.web.repository_url}:${var.web_image_tag}"
      essential = true

      portMappings = [{ containerPort = var.web_port, protocol = "tcp" }]

      environment = [
        { name = "NODE_ENV", value = "production" },
        { name = "PORT", value = tostring(var.web_port) },
        { name = "HOSTNAME", value = "0.0.0.0" },
        { name = "APP_BASE_URL", value = var.public_base_url },
        { name = "AUTH0_DOMAIN", value = var.auth0_domain },
        { name = "AUTH0_CLIENT_ID", value = var.auth0_client_id },
        { name = "AUTH0_AUDIENCE", value = var.oidc_audience },
        # Server-side only. The browser never calls the API directly — every request goes through
        # the BFF — so this is an internal hostname and is deliberately not NEXT_PUBLIC_*.
        { name = "API_BASE_URL", value = var.internal_api_base_url },
      ]

      secrets = [
        { name = "AUTH0_CLIENT_SECRET", valueFrom = var.auth0_client_secret_arn },
        { name = "AUTH0_SECRET", valueFrom = var.web_session_secret_arn },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.web.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "web"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "node -e \"fetch('http://127.0.0.1:${var.web_port}/sign-in').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))\""]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
    },
  ])
}

resource "aws_ecs_service" "web" {
  name            = "${var.name_prefix}-web"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = var.web_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.web_target_group_arn
    container_name   = local.web_container
    container_port   = var.web_port
  }

  health_check_grace_period_seconds = 60

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [var.alb_listener_dependency]
}
