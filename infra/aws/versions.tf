# Provider and tool versions, pinned (docs/TASKS.md T31).
#
# OpenTofu rather than HashiCorp Terraform: Terraform moved to the BUSL licence, and this
# repository has not chosen its own licence yet (docs/DECISIONS.md ADR-006), so taking a BUSL
# build dependency is not a decision to make quietly. The HCL here is standard and works
# unchanged with either binary — substitute `terraform` for `tofu` in every command below.
#
# The AWS provider is pinned to a minor range rather than floating: an infrastructure definition
# that resolves differently next month is not a definition.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # No backend block on purpose. State belongs somewhere durable and shared (S3 + DynamoDB lock),
  # and that bucket has to exist before this configuration can run — a chicken-and-egg that is
  # solved by a human creating it once, at Gate H3, not by this configuration creating its own
  # state store. `tofu init -backend=false` is therefore the only init anyone can run today, which
  # is exactly what `make iac-validate` does.
  #
  # See infra/aws/README.md for the backend block to paste in after Gate H3.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "opspilot"
      Environment = var.environment
      ManagedBy   = "opentofu"
      Repository  = "abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform"
    }
  }
}
