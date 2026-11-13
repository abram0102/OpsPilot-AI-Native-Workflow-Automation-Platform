variable "name_prefix" {
  description = "Prefix for every secret name."
  type        = string
}

variable "recovery_window_days" {
  description = "Deleted-secret recovery window. Not zero: zero makes a mistaken destroy unrecoverable."
  type        = number
  default     = 7
}

variable "db_endpoint" {
  description = "Stored in the database secret for the application to read."
  type        = string
}

variable "db_port" {
  description = "Stored in the database secret."
  type        = number
}

variable "db_name" {
  description = "Stored in the database secret."
  type        = string
}

variable "db_username" {
  description = "Stored in the database secret."
  type        = string
}

variable "db_password" {
  description = "Generated upstream; stored here and never printed."
  type        = string
  sensitive   = true
}

variable "redis_endpoint" {
  description = "Stored in the Redis secret."
  type        = string
}

variable "redis_port" {
  description = "Stored in the Redis secret."
  type        = number
}

variable "redis_auth_token" {
  description = "Generated upstream; stored here and never printed."
  type        = string
  sensitive   = true
}

variable "operator_supplied_secrets" {
  description = <<-EOT
    Secrets this configuration cannot generate, as name => description. An empty slot is created
    for each and never overwritten afterwards.
  EOT
  type        = map(string)

  default = {
    "auth0-client-secret" = "Auth0 application client secret (docs/MANUAL_SETUP.md Gate H1)."
    "llm-api-key"         = "LLM provider API key (Gate H2). Leave as the placeholder to keep AI_MODE=mock."
  }
}
