# ECR repository: private storage for the Docker image. ECS pulls the image from here.
# Full image address will be: <account-id>.dkr.ecr.<region>.amazonaws.com/<app_name>:<tag>

resource "aws_ecr_repository" "app" {
  name = var.app_name

  # Lets `terraform destroy` delete the repository even if it still contains images.
  # Without this, destroy fails until you delete the images by hand.
  force_delete = true
}
