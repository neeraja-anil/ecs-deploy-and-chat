# ECS: the cluster, the task definition (blueprint for the container) and the
# service (keeps N copies running behind the load balancer).

# Where container logs go. View them in the console: CloudWatch -> Log groups.
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.app_name}"
  retention_in_days = 7 # delete old logs automatically
}

# Cluster: a named group for services. With Fargate there are no servers to manage.
resource "aws_ecs_cluster" "main" {
  name = var.app_name
}

# Firewall for the tasks: only the ALB may reach the app port.
resource "aws_security_group" "tasks" {
  name        = "${var.app_name}-tasks"
  description = "Allow traffic to the app only from the load balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "App port from the ALB only"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Allow all outgoing traffic (needed to pull the image from ECR and send logs).
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Task definition: which image, how much CPU/memory, port, env vars, logging.
# Every change creates a new numbered revision (the chat does this too).
resource "aws_ecs_task_definition" "app" {
  family                   = var.app_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # each task gets its own IP (required for Fargate)
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.task_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64" # matches the linux/amd64 image
  }

  container_definitions = jsonencode([{
    name      = var.app_name
    image     = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
    essential = true

    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]

    environment = [
      { name = "APP_MESSAGE", value = "Hello from ECS" }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

# Service: keeps desired_count tasks running, replaces crashed ones,
# and registers them with the load balancer's target group.
resource "aws_ecs_service" "app" {
  name            = var.app_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Give the app 60s to start before failed health checks count against it.
  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true # no NAT gateway, so tasks need a public IP to reach ECR
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = var.app_name
    container_port   = var.container_port
  }

  # The target group must be attached to the ALB (via the listener) before the service uses it.
  depends_on = [aws_lb_listener.http]

  # The chat changes desired_count and task_definition directly in AWS.
  # Without this, the next `terraform apply` would reset them to the values above,
  # undoing the chat's changes.
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }
}
