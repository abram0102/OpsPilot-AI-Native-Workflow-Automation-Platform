output "vpc_id" {
  description = "The VPC."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "For the load balancer. Nothing else belongs here."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "For tasks, the database and the cache."
  value       = aws_subnet.private[*].id
}

output "vpc_cidr" {
  description = "CIDR used for the load balancer's egress rule to the tasks."
  value       = aws_vpc.this.cidr_block
}
