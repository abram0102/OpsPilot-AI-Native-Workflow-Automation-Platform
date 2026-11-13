# tflint configuration (docs/TASKS.md T31 acceptance: "IaC lint/validate passes").
#
# `tofu validate` checks that the configuration is well-formed and internally consistent. It does
# not know that `db.t4g.nano` is not a real instance class, or that an argument was deprecated two
# provider versions ago. The AWS ruleset does, by checking against the provider's own schema —
# which is the difference between "this parses" and "this would apply".

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.44.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

config {
  call_module_type = "all"
}

rule "terraform_naming_convention" {
  enabled = true
}

rule "terraform_documented_variables" {
  enabled = true
}

rule "terraform_documented_outputs" {
  enabled = true
}
