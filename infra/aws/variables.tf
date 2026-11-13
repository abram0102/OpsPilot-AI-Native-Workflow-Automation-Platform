# Every knob that costs money is here, with a staging default set to the smallest thing that
# works. docs/DEPLOYMENT_RUNBOOK.md §4: "Avoid overprovisioning: use the smallest measured service
# sizes". None of these is a measured figure — nothing has run yet — so they are starting points
# chosen to be cheap, and README.md records what each one costs so raising one is a deliberate act.

variable "environment" {
  description = "Environment name. Staging first; production is a separate state and a separate Gate (H4)."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production — these are the only two this configuration models."
  }
}

variable "region" {
  description = "AWS region. No default: the operator picks it at Gate H3, and a default here would be a guess that silently costs money in the wrong place."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR for the VPC. /16 leaves room for three AZs without arithmetic."
  type        = string
  default     = "10.40.0.0/16"
}

variable "availability_zone_count" {
  description = <<-EOT
    How many AZs to spread across.

    Two is the minimum RDS will accept for a subnet group, and it is the staging default: a third
    AZ adds a NAT gateway's hourly charge for redundancy staging does not need. Production should
    raise this to 3.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 3
    error_message = "Between 2 and 3. RDS needs at least two AZs for a subnet group; beyond three is cost with no resilience gain at this size."
  }
}

variable "single_nat_gateway" {
  description = <<-EOT
    Share one NAT gateway across all private subnets instead of one per AZ.

    `true` for staging, and it is the single largest cost decision in this file: a NAT gateway is
    billed hourly *and* per GB regardless of traffic, so one-per-AZ roughly doubles the fixed
    monthly floor. The trade is real — losing that AZ takes outbound connectivity with it — which
    is why production should set this to false.
  EOT
  type        = bool
  default     = true
}

# --- data tier ----------------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is the cheapest Graviton class that runs PostgreSQL 16."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage_gb" {
  description = "Initial storage. gp3 scales up without downtime, so starting small is not a trap."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling. A ceiling, not a target — it exists so a runaway write cannot grow the bill without limit."
  type        = number
  default     = 100
}

variable "db_engine_version" {
  description = <<-EOT
    PostgreSQL major.minor.

    16 because docs/DATA_MODEL.md's migrations are developed against pgvector on 16 and CI runs
    pgvector/pgvector:pg16. The extension's availability on a given RDS version is checked at
    apply time by a data source rather than assumed — see modules/data.
  EOT
  type        = string
  default     = "16.4"
}

variable "db_backup_retention_days" {
  description = "Automated backup retention. Non-zero everywhere: a database with no backups is not a database you can restore."
  type        = number
  default     = 7

  validation {
    condition     = var.db_backup_retention_days >= 1
    error_message = "At least 1. docs/SECURITY_AND_TESTING.md §9 requires a backup to exist before a production migration."
  }
}

variable "db_deletion_protection" {
  description = "Refuse to delete the database. On by default in both environments — `tofu destroy` must not be able to take the data with it."
  type        = bool
  default     = true
}

variable "redis_node_type" {
  description = "ElastiCache node type. cache.t4g.micro is the smallest that supports the Redis Streams this project uses."
  type        = string
  default     = "cache.t4g.micro"
}

# --- compute ------------------------------------------------------------------------------------

variable "api_cpu" {
  description = "Fargate CPU units for the Spring API. 512 = 0.5 vCPU; a JVM under 0.5 starts slowly enough to fail health checks."
  type        = number
  default     = 512
}

variable "api_memory_mb" {
  description = "Fargate memory for the API. 1024 is the minimum Fargate allows with 512 CPU."
  type        = number
  default     = 1024
}

variable "api_desired_count" {
  description = "API tasks. One for staging; production wants at least two so a deploy is not an outage."
  type        = number
  default     = 1
}

variable "worker_cpu" {
  description = "Fargate CPU units for the Python worker. It waits on a provider far more than it computes."
  type        = number
  default     = 256
}

variable "worker_memory_mb" {
  description = "Fargate memory for the worker."
  type        = number
  default     = 512
}

variable "worker_desired_count" {
  description = <<-EOT
    Worker tasks.

    One is correct for staging and is *safe* at any count: T26 proved the business effects
    idempotent under ten concurrent duplicate deliveries, and the Redis consumer group plus the
    per-run lease mean extra workers divide the backlog rather than racing on it.
  EOT
  type        = number
  default     = 1
}

variable "log_retention_days" {
  description = "CloudWatch retention. 30 days for staging; logs are charged for storage, and indefinite retention of support-ticket content is also a privacy question, not only a cost one."
  type        = number
  default     = 30
}

# --- edge ---------------------------------------------------------------------------------------

variable "domain_name" {
  description = <<-EOT
    Public hostname for the ALB, e.g. staging.example.com.

    Empty disables the HTTPS listener and the ACM certificate, which is the default: certificate
    validation requires a Route 53 zone the operator owns, and a configuration that cannot
    validate is a configuration that fails apply halfway through.
  EOT
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "Hosted zone for DNS validation of the certificate. Required when domain_name is set."
  type        = string
  default     = ""
}

variable "alb_ingress_cidrs" {
  description = <<-EOT
    Who may reach the load balancer.

    Defaults to the whole internet because a public application is the point. Narrow it to your
    own address while staging is being brought up: it is the cheapest way to keep an unfinished
    deployment off the public internet, and far more reliable than hoping nobody finds the DNS
    name.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# --- deployment identity --------------------------------------------------------------------------

variable "github_repository" {
  description = "owner/name allowed to assume the deploy role via OIDC. Scoped to exactly this repository; see modules/iam."
  type        = string
  default     = "abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform"
}

variable "github_deploy_ref" {
  description = "The single git ref permitted to deploy. A branch, not a wildcard — a role any branch can assume is a role any pull request can assume."
  type        = string
  default     = "refs/heads/main"
}

variable "oidc_issuer_uri" {
  description = "Auth0 issuer, with scheme and trailing slash (docs/MANUAL_SETUP.md Gate H1)."
  type        = string
}

variable "oidc_audience" {
  description = "API audience the access token must carry."
  type        = string
  default     = "https://api.opspilot.local"
}
