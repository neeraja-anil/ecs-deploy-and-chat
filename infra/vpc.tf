# The network: a VPC with 2 public subnets in 2 Availability Zones,
# an internet gateway, and a route table that sends internet traffic through it.
# No NAT gateway (it costs ~$30+/month); tasks get public IPs instead (see ecs.tf).

# Look up the AZs available in this region (e.g. ap-south-1a, ap-south-1b).
data "aws_availability_zones" "available" {
  state = "available"
}

# Our private network. 10.0.0.0/16 = 65,536 private IP addresses to divide into subnets.
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.app_name }
}

# Two subnets (256 addresses each), each in a different AZ, so one data-centre
# outage doesn't take the service down. The ALB requires at least two AZs.
resource "aws_subnet" "public" {
  count = 2

  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.${count.index + 1}.0/24" # 10.0.1.0/24 and 10.0.2.0/24
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = { Name = "${var.app_name}-public-${count.index + 1}" }
}

# The VPC's door to the internet.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = var.app_name }
}

# Route table: "traffic for any address outside the VPC (0.0.0.0/0) goes to the internet gateway".
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.app_name}-public" }
}

# Attach the route table to both subnets. This is what makes them "public".
resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
