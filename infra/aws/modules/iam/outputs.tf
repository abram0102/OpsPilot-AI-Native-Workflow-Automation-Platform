output "execution_role_arn" {
  description = "Role Fargate uses to start a task."
  value       = aws_iam_role.execution.arn
}

output "api_task_role_arn" {
  description = "Role the API runs as."
  value       = aws_iam_role.api.arn
}

output "worker_task_role_arn" {
  description = "Role the worker runs as."
  value       = aws_iam_role.worker.arn
}

output "deploy_role_arn" {
  description = "Put this in the GitHub Actions workflow's role-to-assume. It is not a secret — it is useless without the OIDC trust condition."
  value       = aws_iam_role.deploy.arn
}
