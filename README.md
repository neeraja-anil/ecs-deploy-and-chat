# ecs-deploy-and-chat

A small Java web service, packaged with Docker, stored in Amazon ECR and run on
AWS ECS (Fargate) behind a load balancer. All AWS infrastructure is created with Terraform.
A chat interface changes the running service from plain-text requests such as "scale to 2".

## Architecture

```
Java app (/health) -> Docker image -> ECR
Terraform: VPC + 2 public subnets (2 AZs) -> ALB (public URL) -> ECS service (Fargate)
Chat page -> chat backend -> OpenAI (text -> JSON action) -> Java validation -> AWS SDK -> ECS
```

```
Internet --HTTP:80--> ALB --8080--> Fargate task(s) running the app
                       |
                       +-- health check: GET /health every 15s
```

## Repository layout

```
app/      Spring Boot service (GET /health, GET /) and Dockerfile
infra/    Terraform: ECR, VPC, IAM, ALB, ECS
chat/     Spring Boot chat interface (web page + backend), runs locally
```

| File | Creates |
|---|---|
| `infra/providers.tf` | AWS provider (>= 6.23), region |
| `infra/variables.tf` | Inputs: app name, image tag, port, desired count, CPU/memory |
| `infra/ecr.tf` | ECR repository for the image |
| `infra/vpc.tf` | VPC, 2 public subnets in 2 AZs, internet gateway, route table |
| `infra/iam.tf` | Task execution role (pull image from ECR, write logs) |
| `infra/alb.tf` | Load balancer, target group (`/health` check), listener on port 80 |
| `infra/ecs.tf` | Log group, cluster, task definition, service |
| `infra/outputs.tf` | Service URL, ECR URL, cluster and service names |

## The app

| Endpoint | Response |
|---|---|
| `GET /health` | `200 {"status":"UP"}` - used by the load balancer health check |
| `GET /` | Status page: the configuration this container is running with. |

### `GET /` - status page

Example response from the service on ECS:

```json
{ "message": "Hello from ECS" }
```

| Field | Comes from | What it shows |
|---|---|---|
| `message` | Env var `APP_MESSAGE` (default `Hi, Welcome!` if not set) | Which configuration the container was started with. On ECS it is set in the task definition (`infra/ecs.tf`). |

The same image behaves differently depending on the environment variables it is started with.
This endpoint makes that visible, so you can confirm a change to the running service
(new message, more tasks, new image) actually reached the containers.

Only `message` is returned. The endpoint is public, so it deliberately does
not list environment variables: a value added later (for example a password) would otherwise
be exposed to anyone with the URL.

Try it locally:

```bash
APP_MESSAGE="hello" java -jar app/target/app.jar
curl localhost:8080/
# {"message":"hello"}
```

## Prerequisites

- Java 17, Maven 3.8+
- Docker
- Terraform (tested with 1.17)
- AWS CLI v2, signed in (`aws login` or `aws configure`)
- For the chat interface: an OpenAI API key
- Region used throughout: `ap-south-1` (Mumbai). Change `region` in `infra/variables.tf` to use another.

## Run locally

```bash
cd app
mvn test                                  # unit tests
mvn package                               # builds target/app.jar
APP_MESSAGE="hello" java -jar target/app.jar
curl localhost:8080/health                # {"status":"UP"}
```

Port 8080 already in use? Run with `SERVER_PORT=8081` instead.

### With Docker

```bash
cd app
docker build --platform linux/amd64 -t ecs-health-check-app .
docker run --rm -p 8080:8080 -e APP_MESSAGE="from Docker" ecs-health-check-app
curl localhost:8080/health
```

The Dockerfile is multi-stage: a Maven + JDK image builds the jar, and a slim JRE image
runs it as a non-root user. The image is built for `linux/amd64` to match Fargate.

## Deploy to AWS

> **Cost:** the load balancer and Fargate task cost roughly **$0.05/hour (~$1.20/day)**
> while running. Run `terraform destroy` when you are done (see below).

Run all commands from the repository root.

**1. Create the ECR repository first.** The ECS service needs an image to exist before it can start.

```bash
terraform -chdir=infra init
terraform -chdir=infra apply -target=aws_ecr_repository.app
```

**2. Build and push the image** (tag `v1`):

```bash
REPO=$(terraform -chdir=infra output -raw ecr_repository_url)

aws ecr get-login-password --region ap-south-1 \
  | docker login --username AWS --password-stdin ${REPO%%/*}

docker build --platform linux/amd64 -t ecs-health-check-app app/
docker tag ecs-health-check-app:latest $REPO:v1
docker push $REPO:v1
```

**3. Create everything else** (VPC, load balancer, ECS cluster and service). Takes about 3-5 minutes.

```bash
terraform -chdir=infra apply
```

**4. Check it works.** The first request can return `503` for a minute while the task starts.

```bash
URL=$(terraform -chdir=infra output -raw service_url)
curl $URL/health     # {"status":"UP"}
curl $URL/           # {"message":"Hello from ECS"}
```

The URL is **HTTP only** (no HTTPS listener). In a browser, type it with `http://` at the start.

## Chat interface

A web page where you type a change in plain English. It is applied to the running ECS service,
and the page shows whether it worked, or a clear message if the request is unclear or not allowed.
It runs on your machine and uses your AWS credentials; it is not deployed to AWS.

### Run it

Requires the service to be deployed (see above) and your AWS CLI to be signed in.

```bash
export OPENAI_API_KEY=<your OpenAI API key>
cd chat
mvn spring-boot:run
```

To keep the key out of your shell history (zsh): `read -s "OPENAI_API_KEY?Key: " && export OPENAI_API_KEY`.

Open **http://localhost:8090**. Changes take about 1-3 minutes, because the reply is sent
only after ECS has finished the change.

### Example requests

| You type | What happens |
|---|---|
| `what's running?` | Shows task count, CPU/memory, image tag and env var names (no change) |
| `scale to 2` | Runs 2 tasks (allowed: 0-5) |
| `set APP_MESSAGE to Good morning` | New task definition revision with the env var; `curl $URL/` then shows it |
| `remove env var APP_MESSAGE` | New revision without that env var |
| `use 0.5 vCPU and 1 GB memory` | New revision with CPU 512 / memory 1024 |
| `deploy image tag v1` | New revision using that image tag (it must exist in ECR) |
| `scale to 10` | Rejected: outside the allowed range |
| `use 0.25 vCPU and 4 GB` | Rejected: not a valid Fargate CPU/memory combination |
| `make it better` | Unclear: shows examples of what works |

### How it works

1. **Read** the current service state from ECS (task count, task definition).
2. **Interpret** the text with the OpenAI API. Structured outputs force the reply into a fixed
   JSON shape with one action from an allow-list (`SCALE`, `SET_RESOURCES`, `SET_ENV`,
   `REMOVE_ENV`, `SET_IMAGE_TAG`, `STATUS`, `UNCLEAR`). Only env var names are sent, not values.
3. **Validate** in Java. The model only suggests; the code decides: task count limit, valid
   Fargate CPU/memory pairs, valid env var names (`AWS_*`/`ECS_*` reserved), image tag exists in ECR.
4. **Apply** with the AWS SDK: `UpdateService` for scaling; for everything else, a new task
   definition revision and then `UpdateService`.
5. **Wait** until ECS reports the service stable (up to 10 minutes) and report the result.

Code layout (`chat/src/main/java/com/healthcheck/chat/`): `web/` (page API), `service/` (the flow),
`ai/` (OpenAI), `validation/` (rules), `aws/` (ECS/ECR calls). The page is
`chat/src/main/resources/static/index.html`.

### Settings

All in `chat/src/main/resources/application.yml`, each overridable with an environment variable:

| Setting | Env var | Default |
|---|---|---|
| Port | `PORT` | `8090` |
| AWS region | `AWS_REGION` | `ap-south-1` |
| ECS cluster / service | `ECS_CLUSTER` / `ECS_SERVICE` | `ecs-health-check-app` (matches the Terraform outputs) |
| Max tasks for scaling | `ECS_MAX_TASKS` | `5` |
| OpenAI model | `OPENAI_MODEL` | `gpt-4o-mini` |
| OpenAI API key | `OPENAI_API_KEY` | none; required, never stored in files |

AWS credentials come from the AWS SDK default credential chain (the same as the AWS CLI),
so no AWS keys are configured in the app.

> Changes made through the chat stay in AWS. For example, after `scale to 2` you pay for
> two tasks until you scale back or run `terraform destroy`.

## Design notes

- **No NAT gateway.** Tasks run in public subnets with a public IP so they can pull the image
  from ECR, which avoids NAT gateway cost (~$30+/month). They are still protected: the task
  security group only accepts port 8080 from the load balancer's security group.
- **Two subnets in two Availability Zones.** Required by the load balancer, and lets the
  service survive the loss of one data centre when running 2+ tasks.
- **`lifecycle { ignore_changes = [desired_count, task_definition] }`** on the ECS service.
  The chat interface changes these directly in AWS; without this, the next `terraform apply`
  would reset them and undo those changes. As a result, `terraform apply` does not roll out
  a new image tag to an existing service.
- **`deregistration_delay = 30`** on the target group (default 300s), so deployments finish
  in about 1-2 minutes.
- **`health_check_grace_period_seconds = 60`**, because Spring Boot takes ~25s to start on
  the smallest Fargate size (0.25 vCPU / 512 MB).
- **Local Terraform state.** `infra/terraform.tfstate` is kept locally and is git-ignored.
  Don't delete it while resources exist, or Terraform can no longer manage or destroy them.

## Clean up

```bash
terraform -chdir=infra destroy
```

This deletes everything, including the ECR repository and its images
(`force_delete = true`). To deploy again, repeat the steps under [Deploy to AWS](#deploy-to-aws).
