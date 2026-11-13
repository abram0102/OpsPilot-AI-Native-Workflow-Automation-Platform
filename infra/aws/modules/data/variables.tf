variable "name_prefix" {
  description = "Prefix for every resource name."
  type        = string
}

variable "vpc_id" {
  description = "VPC the database and cache security groups belong to."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets for the DB and cache subnet groups."
  type        = list(string)
}

variable "application_security_group_id" {
  description = "The tasks' security group. Referenced by the database and cache rules so the allowed set follows deploys instead of being a CIDR somebody widens later."
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL version. Checked for pgvector support at plan time."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "allocated_storage_gb" {
  description = "Initial RDS storage."
  type        = number
}

variable "max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling."
  type        = number
}

variable "backup_retention_days" {
  description = "Automated backup retention."
  type        = number
}

variable "deletion_protection" {
  description = "Refuse to delete the database."
  type        = bool
}

variable "multi_az" {
  description = "A standby in another AZ. Roughly doubles the instance cost; off for staging."
  type        = bool
  default     = false
}

variable "performance_insights_enabled" {
  description = "Off for staging: the free tier covers 7 days of retention, but it is one more thing to reason about before anything has run."
  type        = bool
  default     = false
}

variable "node_type" {
  description = "ElastiCache node type."
  type        = string
}

variable "redis_engine_version" {
  description = "Valkey rather than Redis OSS: ElastiCache prices it lower for an identical Streams API, and Redis' own licence change is the reason the fork exists."
  type        = string
  default     = "7.2"
}

variable "replica_count" {
  description = "Read replicas. 0 for staging — one node, no failover. The outbox is the source of truth, so an event lost here is republished from Postgres."
  type        = number
  default     = 0
}

variable "redis_snapshot_retention_days" {
  description = "0 for staging: the stream is a work queue, not a store of record, and snapshots of it restore nothing anyone wants."
  type        = number
  default     = 0
}
