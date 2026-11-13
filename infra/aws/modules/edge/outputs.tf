output "alb_security_group_id" {
  description = "The load balancer's group, referenced by the tasks' one inbound rule."
  value       = aws_security_group.alb.id
}
output "api_target_group_arn" {
  description = "Target group the API service registers into."
  value       = aws_lb_target_group.api.arn
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
