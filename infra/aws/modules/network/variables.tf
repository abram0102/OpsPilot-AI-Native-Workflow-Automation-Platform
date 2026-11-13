variable "name_prefix" {
  description = "Prefix for every resource name, so two environments in one account never collide."
  type        = string
}

variable "region" {
  description = "AWS region, needed to name the S3 gateway endpoint's service."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
}

variable "availability_zone_count" {
  description = "How many AZs to spread subnets across."
  type        = number
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway instead of one per AZ. The largest cost lever here."
  type        = bool
}
