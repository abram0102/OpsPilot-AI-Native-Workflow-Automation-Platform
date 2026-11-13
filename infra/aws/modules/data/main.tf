# RDS PostgreSQL with pgvector, and ElastiCache Redis. Both private (docs/TASKS.md T31
# acceptance: "DB and Redis private").

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

# --- pgvector availability, checked rather than assumed ------------------------------------------
#
# docs/DEPLOYMENT_RUNBOOK.md §2: "For vector extension on AWS RDS, check target engine+extension
# availability ... prior to launch." This data source does that at plan time: if the requested
# engine version does not offer `vector`, the plan fails with the reason, instead of apply
# succeeding and Flyway's V001 `CREATE EXTENSION vector` failing against a database that already
# exists and already costs money.
data "aws_rds_engine_version" "postgres" {
  engine             = "postgres"
  version            = var.engine_version
  include_all        = false
  preferred_versions = [var.engine_version]
}

locals {
  pgvector_supported = contains(
    [for extension in data.aws_rds_engine_version.postgres.supported_feature_names : extension],
    "vector"
  )
}

# --- credentials ----------------------------------------------------------------------------------

# Generated here and written straight to Secrets Manager by the caller. Never a variable, never a
# default, never in a tfvars file: docs/DEPLOYMENT_RUNBOOK.md §4 — "Do not store database
# credentials in Docker images or source".
#
# It does land in Terraform *state*, which is why the backend bucket must be encrypted and
# access-controlled; infra/aws/README.md says so where the backend block is pasted in.
resource "random_password" "db" {
  length = 32
  # RDS rejects several punctuation characters in a master password, and the failure arrives
  # minutes into an apply. Excluded rather than discovered.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_subnet_ids
  tags       = { Name = "${var.name_prefix}-db" }
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "PostgreSQL. Reachable only from the application tasks."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-db" }
}

# Rules as separate resources rather than inline blocks: an inline `ingress` list is replaced
# wholesale on every change, which has a habit of briefly removing the rule you depend on.
resource "aws_vpc_security_group_ingress_rule" "db_from_tasks" {
  security_group_id = aws_security_group.db.id
  description       = "PostgreSQL from application tasks only"

  # A security group reference, not a CIDR. The set of task IPs changes on every deploy; the
  # group does not, and no subnet-wide rule can be widened by accident later.
  referenced_security_group_id = var.application_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name_prefix}-pg16"
  family = "postgres16"

  # Log any statement over a second. Cheap, and it is the first thing anyone asks for when the
  # API's p95 rises (docs/OBSERVABILITY.md).
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # pgvector has to be loadable before Flyway's V001 can CREATE EXTENSION it.
  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements"
  }

  tags = { Name = "${var.name_prefix}-pg16" }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  # gp3 and storage autoscaling: start at the smallest useful size and let it grow, rather than
  # provisioning for a year of growth on day one.
  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "opspilot"
  username = "opspilot"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.this.name

  # The acceptance criterion, as one line. There is also no route from the internet to the subnet
  # this sits in, so this is the second of two independent reasons it is unreachable.
  publicly_accessible = false

  backup_retention_period = var.backup_retention_days
  # Early morning UTC, and the maintenance window deliberately does not overlap it.
  backup_window      = "03:00-04:00"
  maintenance_window = "Mon:04:30-Mon:05:30"

  # Minor versions apply themselves; majors never do. A major version arriving unannounced could
  # take pgvector or a migration's assumptions with it.
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false

  deletion_protection = var.deletion_protection
  # A final snapshot on delete, always. `skip_final_snapshot = true` is how data disappears.
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-final-${formatdate("YYYYMMDDhhmmss", timestamp())}"

  # Multi-AZ is a production decision: it roughly doubles the instance cost for a standby that
  # staging has no use for.
  multi_az = var.multi_az

  performance_insights_enabled    = var.performance_insights_enabled
  enabled_cloudwatch_logs_exports = ["postgresql"]

  # Applied in the maintenance window rather than immediately: an apply that reboots the database
  # the moment someone changes a tag is a surprise nobody needs.
  apply_immediately = false

  lifecycle {
    # `timestamp()` in the snapshot name changes on every plan, which would otherwise show a
    # spurious diff — and worse, train the reader to skim diffs on the database.
    ignore_changes = [final_snapshot_identifier]

    precondition {
      condition     = local.pgvector_supported
      error_message = "PostgreSQL ${var.engine_version} in this region does not list the `vector` extension, so Flyway's V001 CREATE EXTENSION would fail against a database that already exists and already costs money. Pick a version that does (docs/DEPLOYMENT_RUNBOOK.md §2)."
    }
  }
}

# --- Redis --------------------------------------------------------------------------------------

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "redis" {
  name        = "${var.name_prefix}-redis"
  description = "Redis. Reachable only from the application tasks."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-redis" }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_tasks" {
  security_group_id = aws_security_group.redis.id
  description       = "Redis from application tasks only"

  referenced_security_group_id = var.application_security_group_id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "Redis Streams for the agent event bus (docs/PRODUCT_AND_ARCHITECTURE.md)."

  engine         = "valkey"
  engine_version = var.redis_engine_version
  node_type      = var.node_type
  port           = 6379

  # One node for staging. The stream is a work queue whose durability requirement is modest —
  # T24's outbox is the source of truth, and an event lost in Redis is republished from Postgres.
  # That is what makes a single node defensible here and is the reason it is written down.
  num_cache_clusters         = var.replica_count + 1
  automatic_failover_enabled = var.replica_count > 0
  multi_az_enabled           = var.replica_count > 0

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  # AUTH token rather than open-within-the-VPC: transit encryption without authentication still
  # lets anything that reaches the subnet issue commands.
  auth_token = random_password.redis_auth.result

  snapshot_retention_limit = var.redis_snapshot_retention_days
  maintenance_window       = "tue:05:00-tue:06:00"

  apply_immediately = false

  lifecycle {
    # Rotating the token is a deliberate operation with an application restart attached, not
    # something an unrelated apply should do.
    ignore_changes = [auth_token]
  }
}

resource "random_password" "redis_auth" {
  length = 64
  # ElastiCache AUTH tokens permit a restricted character set.
  special = false
}
