# Application Load Balancer: the public URL. It forwards requests to healthy tasks
# and checks /health to decide which tasks are healthy.

# Firewall for the ALB: allow HTTP (port 80) from anywhere.
resource "aws_security_group" "alb" {
  name        = "${var.app_name}-alb"
  description = "Allow HTTP from the internet to the load balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Allow all outgoing traffic (needed to forward requests to the tasks).
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# The load balancer itself, placed in both public subnets.
resource "aws_lb" "app" {
  name               = var.app_name
  load_balancer_type = "application"
  internal           = false # internet-facing
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}

# Target group: the list of task IPs the ALB sends traffic to, plus the health check.
# ECS adds/removes task IPs here automatically.
resource "aws_lb_target_group" "app" {
  name        = var.app_name
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip" # Fargate tasks are registered by their own IP address
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/health"
    matcher             = "200"
    interval            = 15 # seconds between checks
    healthy_threshold   = 2  # 2 passes in a row -> healthy
    unhealthy_threshold = 3  # 3 failures in a row -> unhealthy
  }

  # When a task is being stopped, wait 30s (default 300s) for open requests to finish.
  # Shorter wait = chat-triggered deployments finish in ~1-2 minutes instead of 5+.
  deregistration_delay = 30
}

# Listener: "accept HTTP on port 80 and forward it to the target group".
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
