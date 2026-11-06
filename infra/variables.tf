# Inputs you can change without editing the other files.
# Override on the command line, e.g.  terraform apply -var="image_tag=v2"

variable "region" {
  description = "AWS region for all resources"
  type        = string
  default     = "ap-south-1" # Mumbai
}

variable "app_name" {
  description = "Name used for the ECR repository, ECS cluster/service and other resources"
  type        = string
  default     = "ecs-health-check-app"
}

variable "image_tag" {
  description = "Image tag in ECR to run (used when the service is first created)"
  type        = string
  default     = "v1"
}

variable "container_port" {
  description = "Port the app listens on inside the container"
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "How many copies (tasks) of the app to run at first"
  type        = number
  default     = 1
}

variable "cpu" {
  description = "CPU units per task (1024 = 1 vCPU). 256 is the smallest Fargate size."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Memory per task in MB. 512 is the smallest allowed with 256 CPU."
  type        = number
  default     = 512
}
