# VPC, subnets and egress (docs/TASKS.md T31; docs/DEPLOYMENT_RUNBOOK.md §4).
#
# The shape is the one the runbook asks for and the one the security boundary in
# docs/SECURITY_AND_TESTING.md §1 requires: "RDS and Redis private subnet/network; ingress only
# through configured HTTPS load balancer/proxy".
#
# Public subnets hold the load balancer and nothing else. Every compute task, the database and the
# cache live in private subnets with no route from the internet — not merely a security group that
# says no, but no route at all, which is the difference between a rule somebody can edit and a
# topology somebody has to rebuild.

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.availability_zone_count)

  # /20 per subnet out of a /16: 4094 usable addresses each, far more than this needs, and the
  # arithmetic stays legible. Public subnets take the low indices, private the high ones, so
  # reading a CIDR tells you which it is.
  public_subnet_cidrs  = [for index in range(var.availability_zone_count) : cidrsubnet(var.vpc_cidr, 4, index)]
  private_subnet_cidrs = [for index in range(var.availability_zone_count) : cidrsubnet(var.vpc_cidr, 4, index + 8)]

  nat_gateway_count = var.single_nat_gateway ? 1 : var.availability_zone_count
}

resource "aws_vpc" "this" {
  cidr_block = var.vpc_cidr

  # Both needed for VPC endpoints and for RDS/ElastiCache private DNS names to resolve.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name_prefix}-vpc" }
}

# --- public: the load balancer only -------------------------------------------------------------

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-igw" }
}

resource "aws_subnet" "public" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.public_subnet_cidrs[count.index]
  availability_zone = local.azs[count.index]

  # Deliberately false. Anything that needs a public address here is the ALB, which brings its
  # own; a task that got one by default would be reachable from the internet by accident, which
  # is precisely the failure this layout exists to prevent.
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# --- private: everything else ---------------------------------------------------------------------

resource "aws_subnet" "private" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_subnet_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = {
    Name = "${var.name_prefix}-private-${local.azs[count.index]}"
    Tier = "private"
  }
}

resource "aws_eip" "nat" {
  count  = local.nat_gateway_count
  domain = "vpc"
  tags   = { Name = "${var.name_prefix}-nat-${count.index}" }
}

resource "aws_nat_gateway" "this" {
  count = local.nat_gateway_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = { Name = "${var.name_prefix}-nat-${count.index}" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "private" {
  count = var.availability_zone_count

  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-private-${local.azs[count.index]}" }
}

resource "aws_route" "private_nat" {
  count = var.availability_zone_count

  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"

  # With a single shared NAT every private subnet routes through the one in AZ 0. That is the
  # cost trade `single_nat_gateway` names, and its consequence stated plainly: losing AZ 0 takes
  # outbound connectivity for every AZ with it.
  nat_gateway_id = aws_nat_gateway.this[var.single_nat_gateway ? 0 : count.index].id
}

resource "aws_route_table_association" "private" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# --- endpoints: keep AWS traffic off the NAT -------------------------------------------------------

# S3 through a gateway endpoint rather than the NAT. Two reasons, and the second is the one that
# matters: NAT charges per GB, and document uploads are the bulk of this application's S3 traffic;
# and traffic to S3 then never leaves the AWS network at all.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id

  tags = { Name = "${var.name_prefix}-s3" }
}
