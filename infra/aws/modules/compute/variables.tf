variable "name_prefix" {
  description = "Prefix for every resource name, so two environments in one account never collide."
  type        = string
}
variable "environment" {
  description = "staging or production. Appears in log group names and the Spring profile."
  type        = string
}
variable "region" {
  description = "AWS region, for the awslogs driver and the SDK default."
  type        = string
}
variable "vpc_id" {
  description = "VPC this module's security groups belong to."
  type        = string
}
variable "private_subnet_ids" {
  description = "Private subnets. Nothing here gets a public address."
  type        = list(string)
}
variable "alb_security_group_id" {
  description = "The load balancer's group, referenced by the tasks' one inbound rule."
  type        = string
}
variable "api_target_group_arn" {
  description = "Target group the API service registers into."
  type        = string
}
variable "alb_listener_dependency" {
  description = "Forces the listener to exist before the service registers targets. ECS fails the service create otherwise."
  type        = any
  default     = null
}

variable "api_port" {
  description = "Port the API listens on inside the container."
  type        = number
  default     = 8080
}

variable "api_cpu" {
  description = "Fargate CPU units for the API task."
  type        = number
}
variable "api_memory_mb" {
  description = "Fargate memory for the API task, in MiB."
  type        = number
}
variable "api_desired_count" {
  description = "How many API tasks to run."
  type        = number
}
variable "worker_cpu" {
  description = "Fargate CPU units for the worker task."
  type        = number
}
variable "worker_memory_mb" {
  description = "Fargate memory for the worker task, in MiB."
  type        = number
}
variable "worker_desired_count" {
  description = "How many worker tasks to run. Safe at any count — see the root variable."
  type        = number
}
variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
}
variable "container_insights_enabled" {
  description = "Per-task CPU/memory in CloudWatch. Off for staging: it is charged per metric and the ALB plus the application's own Prometheus endpoint already answer the questions that matter (docs/OBSERVABILITY.md)."
  type        = bool
  default     = false
}

variable "api_image_tag" {
  description = "Only the first deploy uses this; afterwards the workflow sets the tag and `ignore_changes` keeps this configuration from reverting it."
  type        = string
  default     = "bootstrap"
}

variable "worker_image_tag" {
  description = "Worker image tag. Same story as api_image_tag: set by the deploy workflow after the first apply."
  type        = string
  default     = "bootstrap"
}

variable "ai_mode" {
  description = "mock or real. Staging stays mock until Gate H2 — an environment that can spend money should say so deliberately."
  type        = string
  default     = "mock"

  validation {
    condition     = contains(["mock", "real"], var.ai_mode)
    error_message = "ai_mode must be mock or real."
  }
}

variable "oidc_issuer_uri" {
  description = "Auth0 issuer the API validates tokens against."
  type        = string
}
variable "oidc_audience" {
  description = "Audience the API requires on an access token."
  type        = string
}
variable "execution_role_arn" {
  description = "Role Fargate itself uses to pull images and read injected secrets."
  type        = string
}
variable "api_task_role_arn" {
  description = "Role the API's own code runs as."
  type        = string
}
variable "worker_task_role_arn" {
  description = "Role the worker's own code runs as. Read-only on S3."
  type        = string
}
variable "db_endpoint" {
  description = "Private RDS hostname."
  type        = string
}
variable "db_port" {
  description = "PostgreSQL port."
  type        = number
}
variable "db_name" {
  description = "Database name."
  type        = string
}
variable "db_username" {
  description = "Database user. The password arrives via Secrets Manager, never here."
  type        = string
}
variable "redis_endpoint" {
  description = "Private ElastiCache primary hostname."
  type        = string
}
variable "redis_port" {
  description = "Redis port."
  type        = number
}
variable "documents_bucket_name" {
  description = "S3 bucket holding uploaded knowledge documents."
  type        = string
}
variable "database_secret_arn" {
  description = "Secrets Manager entry holding the database password."
  type        = string
}
variable "redis_secret_arn" {
  description = "Secrets Manager entry holding the Redis AUTH token."
  type        = string
}
variable "internal_service_secret_arn" {
  description = "Secrets Manager entry holding the internal-API signing secret."
  type        = string
}
variable "web_port" {
  description = "Port the Next.js standalone server listens on."
  type        = number
  default     = 3000
}

variable "web_cpu" {
  description = "Fargate CPU units for the web task."
  type        = number
  default     = 256
}

variable "web_memory_mb" {
  description = "Fargate memory for the web task, in MiB."
  type        = number
  default     = 512
}

variable "web_desired_count" {
  description = "How many web tasks to run."
  type        = number
  default     = 1
}

variable "web_image_tag" {
  description = "Web image tag. Set by the deploy workflow after the first apply."
  type        = string
  default     = "bootstrap"
}

variable "web_target_group_arn" {
  description = "Public target group the web service registers into."
  type        = string
}

variable "internal_alb_security_group_id" {
  description = "The internal load balancer's group, allowed to reach the API port."
  type        = string
}

variable "internal_api_base_url" {
  description = "How the web app and worker reach the API. Internal; never leaves the VPC."
  type        = string
}

variable "public_base_url" {
  description = "The URL a browser uses. Auth0 redirects back here, so it must match the application's allowed callback URLs."
  type        = string
}

variable "auth0_domain" {
  description = "Auth0 tenant domain. Not a secret."
  type        = string
}

variable "auth0_client_id" {
  description = "Auth0 application client id. Not a secret — it is in every browser redirect."
  type        = string
}

variable "auth0_client_secret_arn" {
  description = "Secrets Manager entry holding the Auth0 client secret, filled by a human at Gate H3."
  type        = string
}

variable "web_session_secret_arn" {
  description = "Secrets Manager entry holding AUTH0_SECRET, the key the session cookie is encrypted with."
  type        = string
}
