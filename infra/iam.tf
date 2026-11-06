# Task execution role: the permissions ECS uses to START the container,
# i.e. pull the image from ECR and send container logs to CloudWatch.

resource "aws_iam_role" "task_execution" {
  name = "${var.app_name}-task-execution"

  # "Trust policy": only the ECS tasks service may use (assume) this role.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# AWS-managed policy with exactly the permissions above (ECR pull + CloudWatch logs).
resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
