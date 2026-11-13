# infra/aws

OpenTofu configuration for a staging (and later production) deployment — ticket **T31**.

> **Nothing here has ever been applied.** It is format-checked, validated and linted, which proves
> it is well-formed and that every resource argument matches the AWS provider's schema. It does
> not prove it applies cleanly: that needs an AWS account, and that is **Gate H3**
> (`docs/MANUAL_SETUP.md`). Until then, treat it as a reviewed proposal rather than a tested one.

## Why OpenTofu rather than Terraform

The ticket says "Use Terraform or AWS CDK, choose one and document".

HashiCorp relicensed Terraform under the BUSL and moved it out of Homebrew core. OpenTofu is the
Linux Foundation fork, MPL-2.0, and a drop-in: the HCL in this directory is standard and runs
unchanged under either binary — substitute `terraform` for `tofu` in every command below. This
repository has not chosen its own licence yet (`docs/DECISIONS.md` ADR-006), and taking a BUSL
build dependency on its behalf is not a decision to make quietly.

CDK was not chosen for a smaller reason: it would put infrastructure in a third language, in a
repository that already carries Java, Python and TypeScript.

## Layout

```
infra/aws/
  versions.tf  variables.tf  main.tf  outputs.tf
  environments/staging.tfvars.example
  modules/
    network/   VPC, public subnets (ALB only), private subnets, NAT, S3 gateway endpoint
    data/      RDS PostgreSQL 16 + pgvector precondition, ElastiCache Valkey
    storage/   S3: block-public-access, SSE, versioning, lifecycle, TLS-only policy
    compute/   ECR, ECS cluster, Fargate task definitions and services, log groups
    edge/      ALB, ACM certificate, HTTPS listener, HTTP→HTTPS redirect
    iam/       Task roles, execution role, GitHub OIDC deploy role
    secrets/   Secrets Manager entries, generated and operator-supplied
```

## What you can run today

```bash
make iac-validate
```

Format-check, `tofu init -backend=false`, `tofu validate`, and `tflint` over every module. **It
makes no AWS API calls** — `init -backend=false` fetches the provider from the public registry and
configures no state backend. It needs no credentials and cannot create anything.

`make iac-plan` exists and **refuses**, printing the command to run yourself once Gate H3 is open.
Nothing in this repository will run `plan` or `apply` for you.

## Before the first apply — Gate H3

1. **Budget first.** An AWS budget with an alert threshold, before any resource exists. The
   runbook says this and it is the step people skip.
2. **A state backend.** This configuration has no `backend` block, because the bucket holding the
   state has to exist before the configuration that would create it. Create it by hand, then add
   to `versions.tf`:

   ```hcl
   backend "s3" {
     bucket       = "your-opspilot-tfstate"
     key          = "staging/terraform.tfstate"
     region       = "us-east-1"
     encrypt      = true
     use_lockfile = true
   }
   ```

   **Encrypt it and block public access.** State contains the generated database password and
   Redis token in plain text. That is not a flaw in this configuration — it is how Terraform and
   OpenTofu work — and it is why the bucket's access policy matters as much as the database's.
3. **Copy the variables.** `cp environments/staging.tfvars.example environments/staging.tfvars`
   and fill in region and your Auth0 issuer. The real file is gitignored.
4. **Read the plan.** Then, and only then:

   ```bash
   cd infra/aws
   tofu init
   tofu plan -var-file=environments/staging.tfvars
   ```
5. **Fill the secret slots.** The plan creates two placeholders holding the literal string
   `REPLACE-ME-AT-GATE-H3`: `opspilot-staging/auth0-client-secret` and
   `opspilot-staging/llm-api-key`. Set the real values in the Secrets Manager console. The
   configuration has `ignore_changes` on them, so no later apply overwrites what you put there —
   and no secret ever passes through a tfvars file, your shell history, or a plan output.
6. **Check pgvector before you rely on it.** The `data` module has a plan-time precondition that
   fails if the requested PostgreSQL version does not list the `vector` extension. If it passes,
   confirm after apply that Flyway's `V001` actually created the extension — the precondition
   checks availability, not that your parameter group permitted it.

## What this costs

**No figures are quoted here, because I cannot verify them.** Prices differ by region, change
without notice, and a stale number in a README reads as a fact. What follows is the shape of the
bill, ranked, so you can price it yourself in the AWS Pricing Calculator.

| Rank | Component | Why it costs what it does | The lever |
|---|---|---|---|
| 1 | **NAT gateway** | Billed hourly *and* per GB, whether or not anything flows. The single largest fixed cost at this size. | `single_nat_gateway = true` (default) uses one instead of one per AZ. The S3 gateway endpoint already keeps document traffic off it entirely. |
| 2 | **RDS instance** | Hourly, 24/7. Storage and backups on top. | `db_instance_class`; `multi_az` is off for staging and roughly doubles it |
| 3 | **ALB** | Hourly plus capacity units. | None, short of not having one |
| 4 | **ElastiCache node** | Hourly, 24/7. | `redis_node_type`; `replica_count = 0` in staging |
| 5 | **Fargate tasks** | Per vCPU-second and GB-second while running. | `*_cpu`, `*_memory_mb`, `*_desired_count` — all at their minimum |
| 6 | CloudWatch, ECR, Secrets Manager, S3 | Per GB and per secret. Small at this size. | `log_retention_days`, the ECR lifecycle policy |

Every default in `variables.tf` is the cheapest setting that works, and each one documents the
trade it makes. The two that are *resilience* trades rather than pure savings — `single_nat_gateway`
and `multi_az` — say so where they are defined, and production should flip both.

**Staging is not free and does not idle to zero.** RDS, ElastiCache, the ALB and the NAT gateway
all bill by the hour whether or not anyone uses the environment. `tofu destroy` between sessions
is a legitimate way to run this, which is why `db_deletion_protection` exists to stop you doing
it by accident to something you meant to keep.

## Security properties, and where each is enforced

| Property | Where |
|---|---|
| Database unreachable from the internet | No route from a private subnet, **and** `publicly_accessible = false`, **and** a security group that references the tasks' group rather than a CIDR |
| Redis likewise | Same three, plus `transit_encryption_enabled` and an AUTH token |
| HTTPS on the public path | `edge` — HTTP redirects to HTTPS once a domain is set; TLS 1.2 minimum |
| No wildcard admin role | `iam` — every mutating action names its resources. The two `"*"` resources are `ecr:GetAuthorizationToken` and `ecs:RegisterTaskDefinition`, which AWS does not let you scope; both are annotated |
| Deploy role cannot escalate | `iam:PassRole` is restricted to the three task roles. Without that, anything that can register a task definition can attach an administrator role to a container |
| Deploy role is not a shared key | GitHub OIDC, with `sub` pinned to one repository **and** one ref — not `repo:owner/name:*`, which any pull request could match |
| Secrets not in source or images | `secrets` generates what it can and creates never-overwritten slots for what it cannot. Tasks receive them through ECS `secrets`, so they appear in no task definition |
| S3 private | Four public-access blocks, SSE, and a bucket policy denying non-TLS requests |
| Data survives a mistake | `deletion_protection` on the database and the load balancer; `skip_final_snapshot = false`; S3 versioning |

## Known gaps

- **Never applied.** Stated at the top and worth repeating: validate and lint prove form, not
  function. The first real apply will find something, and the pgvector precondition is a guess at
  which something.
- **No WAF.** An ALB without a WAF is fine for staging and is a production conversation.
- **The worker reaches the API through the ALB.** It is inside the VPC so the traffic does not
  leave it, but a service-discovery name would be more direct. ECS Service Connect is the better
  answer and is deferred.
- **No alarms.** `docs/OBSERVABILITY.md` names the thresholds; turning them into CloudWatch alarms
  is not done here, because an alarm with nothing behind it is worse than none.
- **Staging only.** `environment = "production"` is modelled (multi-AZ, a Redis replica) but
  production is Gate **H4**, not H3, and it should have its own state and its own account.
