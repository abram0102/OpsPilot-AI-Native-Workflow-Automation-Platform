output "public_url" {
  description = "Where the application is reachable. Record this only after verifying the deployment (docs/MANUAL_SETUP.md Gate H3)."
  value       = module.edge.public_url
}

output "tls_enabled" {
  description = "False means no domain was configured, so port 80 forwards rather than redirecting to HTTPS."
  value       = module.edge.tls_enabled
}

output "deploy_role_arn" {
  description = "For the GitHub Actions workflow's role-to-assume (T32)."
  value       = module.iam.deploy_role_arn
}

output "api_repository_url" {
  description = "ECR repository for the API image (T32's build workflow pushes here)."
  value       = module.compute.api_repository_url
}
output "worker_repository_url" {
  description = "ECR repository for the worker image."
  value       = module.compute.worker_repository_url
}
output "ecs_cluster_name" {
  description = "ECS cluster the deploy workflow rolls services in."
  value       = module.compute.cluster_name
}

output "database_endpoint" {
  description = "Private. Reachable only from inside the VPC — there is no route from the internet to the subnet it is in."
  value       = module.data.db_endpoint
}

output "secret_names" {
  description = "The slots to fill at Gate H3. The two operator-supplied ones start as a placeholder and are never overwritten by an apply."
  value       = keys(module.secrets.operator_supplied_secret_arns)
}
