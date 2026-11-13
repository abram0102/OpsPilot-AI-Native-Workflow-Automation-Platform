output "alb_security_group_id" {
  description = "The load balancer's group, referenced by the tasks' one inbound rule."
  value       = aws_security_group.alb.id
}
output "alb_dns_name" {
  description = "The load balancer's own DNS name."
  value       = aws_lb.this.dns_name
}
output "public_url" {
  description = "The URL the application is reachable at. Only an https one once a domain is configured."
  value       = var.domain_name != "" ? "https://${var.domain_name}" : "http://${aws_lb.this.dns_name}"
}
output "tls_enabled" {
  description = "False means no domain was configured and port 80 forwards instead of redirecting. Visible in the plan on purpose."
  value       = local.tls_enabled
}
output "listener_dependency" {
  description = "Passed to the ECS service so the listener exists before targets register."
  value       = aws_lb_listener.http.arn
}

output "web_target_group_arn" {
  description = "Public target group the web service registers into."
  value       = aws_lb_target_group.web.arn
}

output "api_target_group_arn" {
  description = "Internal target group the API service registers into."
  value       = aws_lb_target_group.api.arn
}

output "internal_api_base_url" {
  description = "How the web app and the worker reach the API. Never leaves the VPC."
  value       = "http://${aws_lb.internal.dns_name}:${var.api_port}"
}

output "internal_alb_security_group_id" {
  description = "The internal load balancer's group, referenced by the API tasks' ingress rule."
  value       = aws_security_group.internal_alb.id
}
