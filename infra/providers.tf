# Tells Terraform which plugin ("provider") to use to talk to AWS, and which region.
# `terraform init` downloads the provider into .terraform/.

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 6.23+ can read the temporary credentials created by `aws login`
      version = ">= 6.23"
    }
  }
}

# Credentials come from the AWS CLI login (default profile); nothing is hardcoded here.
provider "aws" {
  region = var.region
}
