# Speed Up Java/Maven Builds in AWS CodePipeline Using CodeBuild Dependency Caching

## The Problem: Every Build Downloads the Internet

If you've ever run a production Java/Spring Boot application through AWS CodePipeline, you've likely stared at logs like this:

```
[INFO] Downloading: https://repo.maven.apache.org/maven2/org/springframework/boot/...
[INFO] Downloading: software/amazon/awssdk/s3/2.25.27/s3-2.25.27.jar
[INFO] Downloading: org/hibernate/hibernate-core/6.4.4/hibernate-core-6.4.4.jar
...
```

For a real production Spring Boot app with 30–40 Maven dependencies (Spring Security, JPA, AWS SDK v2, Testcontainers, MapStruct, etc.), this download phase alone can take **3–6 minutes per build**. Multiply that by 20 builds a day across multiple services and you're burning serious time and money.

The fix is simple: **cache the Maven local repository between builds using CodeBuild's built-in cache feature**.

---

## What Gets Cached

Maven stores all downloaded JARs in a local repository, by default at `~/.m2/repository`. On a fresh CodeBuild container (which is ephemeral — it's destroyed after every build), this directory is empty every single time.

By pointing CodeBuild's cache at `/root/.m2/**/*`, the first build populates the cache, and every subsequent build restores it before Maven runs. Maven then skips downloading anything already present.

---

## Project Setup: A Heavy Maven Project

Here's the kind of `pom.xml` that makes this matter. This is a real-world Spring Boot app with:

- Spring Boot Web, JPA, Security, Validation, Actuator, Cache, Redis, Mail, AOP
- PostgreSQL + Flyway + HikariCP
- AWS SDK v2 (S3, SQS, SNS, Secrets Manager, DynamoDB)
- JWT (jjwt)
- SpringDoc OpenAPI / Swagger UI
- MapStruct + Lombok
- Apache Commons
- Micrometer + Prometheus
- Testcontainers (JUnit 5 + PostgreSQL)

Without caching, `mvn clean package` on this project downloads **~150–200 MB** of JARs on every build.

---

## The buildspec.yml — The Key Part

```yaml
version: 0.2

phases:
  install:
    runtime-versions:
      java: corretto17
  pre_build:
    commands:
      - echo "Starting pre-build phase..."
      - mvn --version
  build:
    commands:
      - echo "Build started on `date`"
      - mvn clean package -DskipTests
  post_build:
    commands:
      - echo "Build completed on `date`"
      - ls -lh target/*.jar

artifacts:
  files:
    - target/*.jar
  name: production-app-$(date +%Y%m%d-%H%M%S)

cache:
  paths:
    - '/root/.m2/**/*'        # <-- This is the magic line
```

The `cache.paths` block tells CodeBuild to:
1. **Before the build** — restore `/root/.m2` from the cache store (S3 or local)
2. **After the build** — save the updated `/root/.m2` back to the cache store

---

## Configuring the Cache in CodeBuild Console / CloudFormation

### Option 1: AWS Console

When creating or editing your CodeBuild project:

1. Go to **Edit → Artifacts**
2. Scroll to **Cache**
3. Select **Amazon S3**
4. Choose your S3 bucket (e.g., `my-company-codebuild-cache`)
5. Set a cache prefix like `maven-cache/production-app`

### Option 2: Full CloudFormation Template (Infrastructure as Code)

This is the recommended production approach — everything defined as code in `pipeline.yml`.

The template provisions:
- S3 bucket for build artifacts (with 30-day lifecycle)
- S3 bucket for Maven cache (with 30-day lifecycle)
- IAM roles for CodeBuild and CodePipeline with least-privilege permissions
- CodeBuild project with S3 cache configured
- CodePipeline with GitHub source (via CodeStar Connection) and Build stage

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Description: CodePipeline + CodeBuild with Maven S3 cache

Parameters:
  GitHubOwner:
    Type: String
    Default: jayakrishnayadav24
  GitHubRepo:
    Type: String
    Default: maven-codebuild-cache-demo
  GitHubBranch:
    Type: String
    Default: main
  GitHubConnectionArn:
    Type: String
    Default: arn:aws:codeconnections:us-east-1:863570158116:connection/4af6db6c-6c65-4484-874f-5dbf9f9ba836

Resources:

  ArtifactBucket:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub "maven-demo-artifacts-${AWS::AccountId}"
      LifecycleConfiguration:
        Rules:
          - Id: ExpireOldArtifacts
            Status: Enabled
            ExpirationInDays: 30

  CacheBucket:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub "maven-demo-cache-${AWS::AccountId}"
      LifecycleConfiguration:
        Rules:
          - Id: ExpireMavenCache
            Status: Enabled
            Prefix: maven-cache/
            ExpirationInDays: 30

  CodeBuildRole:
    Type: AWS::IAM::Role
    Properties:
      RoleName: maven-demo-codebuild-role
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal:
              Service: codebuild.amazonaws.com
            Action: sts:AssumeRole
      Policies:
        - PolicyName: CodeBuildPolicy
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action:
                  - logs:CreateLogGroup
                  - logs:CreateLogStream
                  - logs:PutLogEvents
                Resource: "*"
              - Effect: Allow
                Action:
                  - s3:GetObject
                  - s3:PutObject
                  - s3:GetObjectVersion
                Resource:
                  - !Sub "arn:aws:s3:::maven-demo-artifacts-${AWS::AccountId}/*"
                  - !Sub "arn:aws:s3:::maven-demo-cache-${AWS::AccountId}/*"

  CodePipelineRole:
    Type: AWS::IAM::Role
    Properties:
      RoleName: maven-demo-codepipeline-role
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal:
              Service: codepipeline.amazonaws.com
            Action: sts:AssumeRole
      Policies:
        - PolicyName: CodePipelinePolicy
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action:
                  - s3:GetObject
                  - s3:PutObject
                  - s3:GetBucketVersioning
                Resource:
                  - !Sub "arn:aws:s3:::maven-demo-artifacts-${AWS::AccountId}"
                  - !Sub "arn:aws:s3:::maven-demo-artifacts-${AWS::AccountId}/*"
              - Effect: Allow
                Action:
                  - codebuild:BatchGetBuilds
                  - codebuild:StartBuild
                Resource: !GetAtt CodeBuildProject.Arn
              - Effect: Allow
                Action:
                  - codestar-connections:UseConnection
                Resource: !Ref GitHubConnectionArn

  CodeBuildProject:
    Type: AWS::CodeBuild::Project
    Properties:
      Name: maven-codebuild-cache-demo
      ServiceRole: !GetAtt CodeBuildRole.Arn
      Artifacts:
        Type: CODEPIPELINE
      Environment:
        Type: LINUX_CONTAINER
        ComputeType: BUILD_GENERAL1_MEDIUM
        Image: aws/codebuild/standard:7.0
      Source:
        Type: CODEPIPELINE
        BuildSpec: buildspec.yml
      Cache:
        Type: S3
        Location: !Sub "maven-demo-cache-${AWS::AccountId}/maven-cache/production-app"
      LogsConfig:
        CloudWatchLogs:
          Status: ENABLED
          GroupName: /aws/codebuild/maven-codebuild-cache-demo

  Pipeline:
    Type: AWS::CodePipeline::Pipeline
    Properties:
      Name: maven-codebuild-cache-demo-pipeline
      RoleArn: !GetAtt CodePipelineRole.Arn
      ArtifactStore:
        Type: S3
        Location: !Ref ArtifactBucket
      Stages:
        - Name: Source
          Actions:
            - Name: GitHub_Source
              ActionTypeId:
                Category: Source
                Owner: AWS
                Provider: CodeStarSourceConnection
                Version: '1'
              Configuration:
                ConnectionArn: !Ref GitHubConnectionArn
                FullRepositoryId: !Sub "${GitHubOwner}/${GitHubRepo}"
                BranchName: !Ref GitHubBranch
                OutputArtifactFormat: CODE_ZIP
                DetectChanges: true
              OutputArtifacts:
                - Name: SourceOutput
        - Name: Build
          Actions:
            - Name: Maven_Build
              ActionTypeId:
                Category: Build
                Owner: AWS
                Provider: CodeBuild
                Version: '1'
              Configuration:
                ProjectName: !Ref CodeBuildProject
              InputArtifacts:
                - Name: SourceOutput
              OutputArtifacts:
                - Name: BuildOutput

Outputs:
  PipelineURL:
    Value: !Sub "https://console.aws.amazon.com/codesuite/codepipeline/pipelines/maven-codebuild-cache-demo-pipeline/view?region=${AWS::Region}"
  ArtifactBucket:
    Value: !Ref ArtifactBucket
  CacheBucket:
    Value: !Ref CacheBucket
```

### Option 3: AWS CLI

```bash
aws codebuild update-project \
  --name production-app-build \
  --cache type=S3,location=my-company-codebuild-cache/maven-cache/production-app
```

---

## Deploying the CloudFormation Stack

With `pipeline.yml` saved in your repo root, deploy the entire pipeline infrastructure with a single command:

**Step 1 — Deploy the stack:**
```bash
aws cloudformation deploy \
  --template-file pipeline.yml \
  --stack-name maven-codebuild-cache-demo \
  --capabilities CAPABILITY_NAMED_IAM \
  --region us-east-1
```

`--capabilities CAPABILITY_NAMED_IAM` is required because the template creates named IAM roles. You'll see:

```
Waiting for changeset to be created..
Waiting for stack create/update to complete
Successfully created/updated stack - maven-codebuild-cache-demo
```

That's it. Once deployed:
- Pipeline auto-triggers on every push to `main`
- First build → downloads all Maven deps (~4–6 min), saves to S3 cache
- Every build after → restores from S3 cache (~10–20 sec for deps)

**Step 2 — Check stack status:**
```bash
aws cloudformation describe-stacks \
  --stack-name maven-codebuild-cache-demo \
  --region us-east-1 \
  --query "Stacks[0].StackStatus"
```

**Step 3 — View stack outputs (pipeline URL, bucket names):**
```bash
aws cloudformation describe-stacks \
  --stack-name maven-codebuild-cache-demo \
  --region us-east-1 \
  --query "Stacks[0].Outputs"
```

**To delete everything when done:**
```bash
aws cloudformation delete-stack \
  --stack-name maven-codebuild-cache-demo \
  --region us-east-1
```

> Note: Empty the S3 buckets manually before deleting the stack, as CloudFormation cannot delete non-empty buckets.

Once deployed, the pipeline auto-triggers on every push to `main` via the GitHub CodeStar Connection.

---

## How the Cache Flow Works in CodePipeline

```
CodePipeline Trigger (push to main)
        │
        ▼
  CodeBuild starts
        │
        ▼
  Restore cache from S3
  /root/.m2 ← s3://my-bucket/maven-cache/production-app
        │
        ▼
  mvn clean package
  (Maven finds JARs locally, skips downloads)
        │
        ▼
  Save updated cache back to S3
        │
        ▼
  Upload artifact (JAR) to S3
        │
        ▼
  CodeDeploy / ECS / Lambda deploy
```

---

## Seeing It in Action: Before vs After Cache

### Build 1 — Cold Start (No Cache)

The first time the pipeline runs, CodeBuild has an empty `/root/.m2`. Every single JAR gets downloaded from Maven Central. You can see hundreds of download lines in the logs — Spring Boot, AWS SDK, Hibernate, Spark, Hadoop, and more — all being pulled fresh.

<img width="1816" height="762" alt="Build 1 cold start - all dependencies downloading from Maven Central" src="https://github.com/user-attachments/assets/94ebdb26-142f-4542-bb20-81740490cd38" />

At the end of Build 1, CodeBuild automatically uploads the populated `/root/.m2` to your S3 cache bucket. Here's the cache saved in S3 after the first build:

<img width="1601" height="630" alt="S3 cache bucket populated after Build 1" src="https://github.com/user-attachments/assets/64d9f9ec-507e-4d3c-9773-20f81d5eb441" />

<img width="1872" height="620" alt="Maven cache files stored in S3 prefix" src="https://github.com/user-attachments/assets/13d66f98-1bdb-417a-aad1-8909d1ec5d32" />

---

### Build 2 — Warm Cache (No Downloads)

On the second build, CodeBuild restores `/root/.m2` from S3 before Maven runs. There are zero download lines in the logs. The build goes straight to compilation.

<img width="1597" height="748" alt="Build 2 warm cache - no downloads, build completes fast" src="https://github.com/user-attachments/assets/efbb03ed-3772-4584-a336-339ea7414f83" />

You can also see the cache restore happening at the top of the build logs:

<img width="1583" height="711" alt="CodeBuild restoring Maven cache from S3 at build start" src="https://github.com/user-attachments/assets/43aac276-9cb7-4786-8ece-1f2fcefab533" />

---

### Build Duration Comparison

The pipeline execution history shows both runs side by side — the time difference is visible at a glance:

<img width="1697" height="731" alt="Pipeline execution history showing Build 1 vs Build 2 duration" src="https://github.com/user-attachments/assets/34586318-17f2-4fa2-8050-8f82e36dcc56" />



---

## Real Build Time Comparison

| Scenario | Dependency Download Time | Total Build Time |
|---|---|---|
| No cache (cold start) | ~4–6 minutes | ~8–10 minutes |
| With S3 cache (warm) | ~10–20 seconds | ~2–3 minutes |
| Savings | **~5 minutes per build** | **~60–70% faster** |

For a team running 15–20 builds/day, that's **1–2 hours of saved build time daily** per service.

---

## GitHub to Pipeline: How the Trigger Works

The pipeline uses **AWS CodeStar Connections** (now called CodeConnections) to connect to GitHub. This is the modern, webhook-based approach replacing the old OAuth token method.

```
Git push to main
       │
       ▼
GitHub webhook → AWS CodeConnections
       │
       ▼
CodePipeline Source stage triggers
       │
       ▼
CodeBuild picks up the source ZIP
       │
       ▼
buildspec.yml executes
```

The key config in the CloudFormation template that enables auto-trigger:
```yaml
Configuration:
  ConnectionArn: !Ref GitHubConnectionArn
  FullRepositoryId: jayakrishnayadav24/maven-codebuild-cache-demo
  BranchName: main
  DetectChanges: true    # <-- enables auto-trigger on push
```

---

## Cache Invalidation: When Does It Refresh?

CodeBuild's S3 cache is additive — it only adds new files, never removes old ones. This means:

- **New dependency added to pom.xml** → downloaded on next build, added to cache automatically ✅
- **Dependency version bumped** → new version downloaded, old version stays in cache (harmless) ✅
- **Stale/corrupted cache** → manually invalidate by deleting the S3 prefix:

```bash
aws s3 rm s3://my-company-codebuild-cache/maven-cache/production-app --recursive
```

The next build will do a full cold download and repopulate the cache cleanly.

---

## Pro Tips for Production

**1. Use a dedicated S3 bucket for cache**
Keep build cache separate from artifact storage. Enable lifecycle rules to auto-delete cache objects after 30 days to avoid stale bloat.

```yaml
# S3 Lifecycle rule for cache bucket
- ID: ExpireMavenCache
  Status: Enabled
  Prefix: maven-cache/
  Expiration:
    Days: 30
```

**2. Use `BUILD_GENERAL1_MEDIUM` or larger compute**
Larger compute types have faster network I/O, which speeds up both the S3 cache restore and the Maven build itself.

**3. Separate cache prefixes per project/branch**
```
s3://my-cache-bucket/maven-cache/service-a/
s3://my-cache-bucket/maven-cache/service-b/
```
This prevents cache collisions in a multi-service pipeline.

**4. Add `-o` (offline) flag after first warm build**
Once the cache is warm, you can add `mvn clean package -DskipTests -o` (offline mode) to prevent Maven from even attempting to check for updates. This shaves off additional seconds.

**5. Cache Docker layers too**
If you're building Docker images in CodeBuild, combine Maven cache with Docker layer caching using `--cache-from` for maximum speed.

---

## Summary

| What | How |
|---|---|
| What to cache | `/root/.m2/**/*` |
| Cache type | Amazon S3 |
| Where to configure | `buildspec.yml` → `cache.paths` + CodeBuild project cache settings |
| Time saved | 60–70% faster builds on warm cache |
| Cache invalidation | Delete S3 prefix manually when needed |

The change is literally 3 lines in your `buildspec.yml` and a one-time S3 bucket configuration. For any production Java/Maven project with a non-trivial dependency tree, this is one of the highest ROI optimizations you can make to your CI/CD pipeline.
