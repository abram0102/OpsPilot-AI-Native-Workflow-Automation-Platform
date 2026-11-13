output "cluster_name" {
  description = "ECS cluster name, for the deploy workflow."
  value       = aws_ecs_cluster.this.name
}
output "tasks_security_group_id" {
  description = "The tasks' security group, referenced by the DB and cache ingress rules."
  value       = aws_security_group.tasks.id
}
output "api_service_arn" {
  description = "API service, for the deploy role's scoped UpdateService permission."
  value       = aws_ecs_service.api.id
}
output "worker_service_arn" {
  description = "Worker service, same reason."
  value       = aws_ecs_service.worker.id
}
output "api_repository_url" {
  description = "ECR repository the API image is pushed to."
  value       = aws_ecr_repository.api.repository_url
}
output "worker_repository_url" {
  description = "ECR repository the worker image is pushed to."
  value       = aws_ecr_repository.worker.repository_url
}
output "ecr_repository_arns" {
  description = "Both repositories, for the deploy role's push policy."
  value       = [aws_ecr_repository.api.arn, aws_ecr_repository.worker.arn]
}

output "web_service_arn" {
  description = "Web service, for the deploy role's scoped UpdateService permission."
  value       = aws_ecs_service.web.id
}

output "web_repository_url" {
  description = "ECR repository the web image is pushed to."
  value       = aws_ecr_repository.web.repository_url
}

output "cluster_arn" {
  description = "Cluster ARN, used to condition the deploy role's RunTask permission."
  value       = aws_ecs_cluster.this.arn
}
