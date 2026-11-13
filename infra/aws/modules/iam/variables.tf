variable "name_prefix" {
  description = "Prefix for every role name."
  type        = string
}

variable "documents_bucket_arn" {
  description = "Bucket the task policies are scoped to."
  type        = string
}

variable "secret_arns" {
  description = "Exactly the secrets the execution role may read. A list, never a wildcard."
  type        = list(string)
}

variable "ecr_repository_arns" {
  description = "Repositories the deploy role may push to."
  type        = list(string)
}

variable "ecs_service_arns" {
  description = "Services the deploy role may update."
  type        = list(string)
  default     = []
}

variable "github_repository" {
  description = "owner/name permitted to assume the deploy role."
  type        = string
}

variable "github_deploy_ref" {
  description = "The single git ref permitted to deploy."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "The provider is a per-account singleton. False if the account already has one — a second is an error."
  type        = bool
  default     = true
}

variable "existing_github_oidc_provider_arn" {
  description = "Required when create_github_oidc_provider is false."
  type        = string
  default     = ""
}

variable "ecs_cluster_arn" {
  description = "Cluster the deploy role may run the migration task in. Conditions RunTask so it cannot run a task anywhere else."
  type        = string
}
