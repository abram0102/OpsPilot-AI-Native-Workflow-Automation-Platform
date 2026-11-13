# The load balancer and TLS (docs/TASKS.md T31 acceptance: "HTTPS public app path").
#
# This is the only thing in the whole configuration with a public address. Everything behind it
# lives in a private subnet with no route to the internet.

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

locals {
  # TLS is configured only when the operator has a domain and a hosted zone. Without them ACM
  # cannot validate a certificate, and an apply would fail halfway through having already created
  # a load balancer that bills by the hour.
  tls_enabled = var.domain_name != "" && var.route53_zone_id != ""
}

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Public ingress. The only security group in this configuration that allows it."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  for_each = local.tls_enabled ? toset(var.ingress_cidrs) : toset([])

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# Port 80 exists only to redirect. It carries no application traffic.
resource "aws_vpc_security_group_ingress_rule" "http_redirect" {
  for_each = toset(var.ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTP, redirected to HTTPS"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "to_tasks" {
  security_group_id = aws_security_group.alb.id
  description       = "To the application tasks"

  cidr_ipv4   = var.vpc_cidr
  ip_protocol = "-1"
}

resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]

  # Refuse to delete while this is true. A load balancer is the public face of the deployment and
  # `tofu destroy` should not be able to take it out as a side effect.
  enable_deletion_protection = var.deletion_protection

  # Strips malformed headers rather than passing them to the application — the cheap half of
  # request-smuggling defence.
  drop_invalid_header_fields = true

  idle_timeout = 60

  dynamic "access_logs" {
    for_each = var.access_logs_bucket == "" ? [] : [1]
    content {
      bucket  = var.access_logs_bucket
      prefix  = "alb"
      enabled = true
    }
  }

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_lb_target_group" "api" {
  name        = "${var.name_prefix}-api"
  port        = var.api_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    enabled = true
    # The readiness probe, not liveness: readiness is the one that reports a dependency being
    # unavailable, and a task that cannot reach its database should not receive traffic.
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
    matcher             = "200"
  }

  # Drain in flight requests, briefly. The default 300s makes every deploy five minutes long.
  deregistration_delay = 30

  lifecycle {
    create_before_destroy = true
  }
}

# --- certificate ----------------------------------------------------------------------------

resource "aws_acm_certificate" "this" {
  count = local.tls_enabled ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = local.tls_enabled ? {
    for option in aws_acm_certificate.this[0].domain_validation_options :
    option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  } : {}

  zone_id         = var.route53_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  count = local.tls_enabled ? 1 : 0

  certificate_arn         = aws_acm_certificate.this[0].arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

# --- listeners --------------------------------------------------------------------------------

resource "aws_lb_listener" "https" {
  count = local.tls_enabled ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  # TLS 1.2 minimum, forward secrecy only. The AWS default policy still permits older suites.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = aws_acm_certificate_validation.this[0].certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# With TLS configured, port 80 only redirects. Without it (no domain yet), port 80 forwards —
# otherwise a staging environment brought up before DNS exists has no reachable path at all, and
# the operator cannot check anything. The variable's documentation says this plainly, and
# `tls_enabled` being false is visible in the plan.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.tls_enabled ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.tls_enabled ? [] : [1]
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.api.arn
    }
  }
}

resource "aws_route53_record" "alias" {
  count = local.tls_enabled ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}
