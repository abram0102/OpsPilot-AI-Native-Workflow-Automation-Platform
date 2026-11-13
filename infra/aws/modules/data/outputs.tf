output "db_endpoint" {
  description = "Private RDS hostname."
  value       = aws_db_instance.this.address
}

output "db_port" {
  description = "PostgreSQL port."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name."
  value       = aws_db_instance.this.db_name
}

output "db_username" {
  description = "Master username."
  value       = aws_db_instance.this.username
}

output "db_password" {
  description = "Written to Secrets Manager by the caller and never printed. Marked sensitive so it cannot reach a CI log through an output."
  value       = random_password.db.result
  sensitive   = true
}

output "redis_primary_endpoint" {
  description = "Private ElastiCache primary hostname."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "redis_port" {
  description = "Redis port."
  value       = aws_elasticache_replication_group.this.port
}

output "redis_auth_token" {
  description = "Generated AUTH token. Sensitive."
  value       = random_password.redis_auth.result
  sensitive   = true
}

output "db_security_group_id" {
  description = "The database's security group."
  value       = aws_security_group.db.id
}

output "redis_security_group_id" {
  description = "The cache's security group."
  value       = aws_security_group.redis.id
}
