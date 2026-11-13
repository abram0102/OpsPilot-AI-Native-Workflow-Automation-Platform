variable "name_prefix" {
  description = "Prefix for the bucket name."
  type        = string
}

variable "noncurrent_version_retention_days" {
  description = "How long a superseded object version is kept. Long enough to undo a mistake, short enough not to be a growing bill."
  type        = number
  default     = 30
}
