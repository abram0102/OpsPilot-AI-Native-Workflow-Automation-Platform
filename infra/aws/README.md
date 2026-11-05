# infra/aws

Terraform or AWS CDK (one chosen and documented) for staging/production: private VPC, RDS PostgreSQL with pgvector, Redis-compatible service, private S3, ECS Fargate, ALB/TLS, IAM, CloudWatch, secrets manager. Not yet implemented — see ticket **T31** in `docs/TASKS.md`.

**Human gate H3 applies** (`docs/MANUAL_SETUP.md`): no `apply` runs without the repository owner's AWS account, budget approval, and reviewed plan. `plan`-only commands are safe to build ahead of that approval; nothing here auto-applies or incurs cost.
