variable "name_prefix" {
  description = "Prefix for every resource name, so two environments in one account never collide."
  type        = string
}
variable "vpc_id" {
  description = "VPC this module's security groups belong to."
  type        = string
}
variable "vpc_cidr" {
  description = "CIDR used for the load balancer's egress rule to the tasks."
  type        = string
}
variable "public_subnet_ids" {
  description = "Public subnets for the load balancer — the only public-facing thing."
  type        = list(string)
}
variable "api_port" {
  description = "Port the target group forwards to."
  type        = number
  default     = 8080
}
variable "ingress_cidrs" {
  description = "Who may reach the load balancer."
  type        = list(string)
}
variable "domain_name" {
  description = "Public hostname. Empty disables TLS and the certificate."
  type        = string
}
variable "route53_zone_id" {
  description = "Hosted zone for certificate validation and the alias record."
  type        = string
}
variable "deletion_protection" {
  description = "Refuse to delete the load balancer."
  type        = bool
  default     = true
}
variable "access_logs_bucket" {
  description = "Empty disables access logs. They need a bucket with an AWS-service-specific policy, which is a separate decision from this module."
  type        = string
  default     = ""
}

variable "web_port" {
  description = "Port the Next.js standalone server listens on."
  type        = number
  default     = 3000
}

variable "private_subnet_ids" {
  description = "Private subnets for the internal API load balancer."
  type        = list(string)
}
