# The staging/production root module (docs/TASKS.md T31).
#
# Module order follows the dependency chain, and one ordering detail is load-bearing: `compute`
# owns the tasks' security group, and `data` references it. So compute is created before data,
# and the database's ingress rule names a group that already exists rather than a CIDR.

locals {
  name_prefix = "opspilot-${var.environment}"
}

module "network" {
  source = "./modules/network"

  name_prefix             = local.name_prefix
  region                  = var.region
  vpc_cidr                = var.vpc_cidr
  availability_zone_count = var.availability_zone_count
  single_nat_gateway      = var.single_nat_gateway
}

module "storage" {
  source = "./modules/storage"

  name_prefix = local.name_prefix
}

module "edge" {
  source = "./modules/edge"

  name_prefix       = local.name_prefix
  vpc_id            = module.network.vpc_id
  vpc_cidr          = module.network.vpc_cidr
  public_subnet_ids = module.network.public_subnet_ids
  ingress_cidrs     = var.alb_ingress_cidrs
  domain_name       = var.domain_name
  route53_zone_id   = var.route53_zone_id
}

module "data" {
  source = "./modules/data"

  name_prefix                   = local.name_prefix
  vpc_id                        = module.network.vpc_id
  private_subnet_ids            = module.network.private_subnet_ids
  application_security_group_id = module.compute.tasks_security_group_id

  engine_version           = var.db_engine_version
  instance_class           = var.db_instance_class
  allocated_storage_gb     = var.db_allocated_storage_gb
  max_allocated_storage_gb = var.db_max_allocated_storage_gb
  backup_retention_days    = var.db_backup_retention_days
  deletion_protection      = var.db_deletion_protection
  multi_az                 = var.environment == "production"

  node_type = var.redis_node_type
  # A replica in production, none in staging.
  replica_count = var.environment == "production" ? 1 : 0
}

module "secrets" {
  source = "./modules/secrets"

  name_prefix = local.name_prefix

  db_endpoint = module.data.db_endpoint
  db_port     = module.data.db_port
  db_name     = module.data.db_name
  db_username = module.data.db_username
  db_password = module.data.db_password

  redis_endpoint   = module.data.redis_primary_endpoint
  redis_port       = module.data.redis_port
  redis_auth_token = module.data.redis_auth_token
}

module "iam" {
  source = "./modules/iam"

  name_prefix          = local.name_prefix
  documents_bucket_arn = module.storage.bucket_arn
  secret_arns          = module.secrets.all_secret_arns
  ecr_repository_arns  = module.compute.ecr_repository_arns
  ecs_service_arns     = [module.compute.api_service_arn, module.compute.worker_service_arn]
  github_repository    = var.github_repository
  github_deploy_ref    = var.github_deploy_ref
}

module "compute" {
  source = "./modules/compute"

  name_prefix        = local.name_prefix
  environment        = var.environment
  region             = var.region
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids

  alb_security_group_id   = module.edge.alb_security_group_id
  api_target_group_arn    = module.edge.api_target_group_arn
  api_internal_host       = module.edge.alb_dns_name
  alb_listener_dependency = module.edge.listener_dependency

  api_cpu              = var.api_cpu
  api_memory_mb        = var.api_memory_mb
  api_desired_count    = var.api_desired_count
  worker_cpu           = var.worker_cpu
  worker_memory_mb     = var.worker_memory_mb
  worker_desired_count = var.worker_desired_count
  log_retention_days   = var.log_retention_days

  oidc_issuer_uri = var.oidc_issuer_uri
  oidc_audience   = var.oidc_audience

  execution_role_arn   = module.iam.execution_role_arn
  api_task_role_arn    = module.iam.api_task_role_arn
  worker_task_role_arn = module.iam.worker_task_role_arn

  db_endpoint           = module.data.db_endpoint
  db_port               = module.data.db_port
  db_name               = module.data.db_name
  db_username           = module.data.db_username
  redis_endpoint        = module.data.redis_primary_endpoint
  redis_port            = module.data.redis_port
  documents_bucket_name = module.storage.bucket_name

  database_secret_arn         = module.secrets.database_secret_arn
  redis_secret_arn            = module.secrets.redis_secret_arn
  internal_service_secret_arn = module.secrets.internal_service_secret_arn
}
