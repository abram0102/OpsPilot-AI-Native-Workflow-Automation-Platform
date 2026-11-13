output "database_secret_arn" {
  description = "Database connection secret."
  value       = aws_secretsmanager_secret.database.arn
}

output "redis_secret_arn" {
  description = "Redis connection secret."
  value       = aws_secretsmanager_secret.redis.arn
}

output "internal_service_secret_arn" {
  description = "Internal-API signing secret."
  value       = aws_secretsmanager_secret.internal_service.arn
}

output "operator_supplied_secret_arns" {
  description = "The slots a human fills at Gate H3, by name."
  value       = { for name, secret in aws_secretsmanager_secret.operator_supplied : name => secret.arn }
}

output "all_secret_arns" {
  description = "Everything the task roles need read access to — so the IAM policy lists exactly these and not a wildcard."
  value = concat(
    [
      aws_secretsmanager_secret.database.arn,
      aws_secretsmanager_secret.redis.arn,
      aws_secretsmanager_secret.internal_service.arn,
    ],
    [for secret in aws_secretsmanager_secret.operator_supplied : secret.arn],
  )
}
