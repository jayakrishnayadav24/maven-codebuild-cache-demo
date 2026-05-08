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

### Option 2: CloudFormation

```yaml
Resources:
  CodeBuildProject:
    Type: AWS::CodeBuild::Project
    Properties:
      Name: production-app-build
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
        Location: !Sub "${CacheBucket}/maven-cache/production-app"
```

### Option 3: AWS CLI

```bash
aws codebuild update-project \
  --name production-app-build \
  --cache type=S3,location=my-company-codebuild-cache/maven-cache/production-app
```

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

## Real Build Time Comparison

| Scenario | Dependency Download Time | Total Build Time |
|---|---|---|
| No cache (cold start) | ~4–6 minutes | ~8–10 minutes |
| With S3 cache (warm) | ~10–20 seconds | ~2–3 minutes |
| Savings | **~5 minutes per build** | **~60–70% faster** |

For a team running 15–20 builds/day, that's **1–2 hours of saved build time daily** per service.

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
