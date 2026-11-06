# Values Terraform prints after `apply`. Show them again any time with `terraform output`.

output "ecr_repository_url" {
  description = "Push the image here: docker push <this>:<tag>"
  value       = aws_ecr_repository.app.repository_url
}

output "service_url" {
  description = "Public URL of the service (try <this>/health)"
  value       = "http://${aws_lb.app.dns_name}"
}

output "cluster_name" {
  description = "ECS cluster name (used by the chat app)"
  value       = aws_ecs_cluster.main.name
}

output "service_name" {
  description = "ECS service name (used by the chat app)"
  value       = aws_ecs_service.app.name
}
