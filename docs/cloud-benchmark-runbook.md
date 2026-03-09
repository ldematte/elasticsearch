# Running JMH Benchmarks on a Cloud Instance

## Prerequisites
- AWS CLI configured
- OKTA authentication set up (`mfa` command available)

## Steps

### 1. Authenticate with AWS via OKTA
Run `mfa elastic-dev-saml` (or `okta-aws-cli --short-user-agent --profile elastic-dev-saml`) in the terminal
and complete the interactive OKTA authentication.

The profile **must** be `elastic-dev-saml` (not the default "commercial" profile, which lacks EC2 permissions).

After authenticating, either:
- `export AWS_PROFILE=elastic-dev-saml`, or
- pass `--profile elastic-dev-saml` to every `aws` command.

### 2. Identify and connect to the EC2 instance
List instances tagged with `project=lorenzodematte` in `us-east-1`:
```
aws ec2 describe-instances --profile elastic-dev-saml --region us-east-1 \
  --filters "Name=tag:project,Values=lorenzodematte" \
  --query 'Reservations[*].Instances[*].[InstanceId,InstanceType,State.Name,Tags[?Key==`Name`].Value|[0]]' \
  --output table
```

Available instances:
- `intel-benchmarks` (c8i.2xlarge) — Intel x86, usually stopped
- `amd-benchmarking` (c8a.xlarge) — AMD x86, typically running
- `arm-benchmark` (c8gd.xlarge) — ARM, typically running
- `ldematte-nvidia-test` (g6.8xlarge) — GPU, usually stopped

Get the public DNS:
```
aws ec2 describe-instances --profile elastic-dev-saml --region us-east-1 \
  --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].PublicDnsName' --output text
```

SSH connection:
```
ssh -i ~/.ssh/ldematte-us-aws.pem admin@<PUBLIC_DNS>
```

### 3. Build the native library and install it

From the repo root (`~/elasticsearch`):

```bash
cd libs/simdvec/native

# Build the native shared library (no cache to ensure fresh build)
./gradlew --no-build-cache clean vecAmd64SharedLibrary

# Copy to the expected platform directory
cp build/libs/vec/shared/amd64/libvec.so ../../native/libraries/build/platform/linux-x64/libvec.so
```

Then disable pulling of the pre-built versioned libvec. Two options:
- **Option A (env var, must be set before every gradle command):**
  `export LOCAL_VEC_BINARY_OS=linux`
- **Option B (edit build.gradle, "safer" but dirties working tree):**
  Comment out `libs "org.elasticsearch:vec:${vecVersion}@zip"` in
  `libs/native/libraries/build.gradle`

### 4. Run the JMH benchmark

From the repo root (`~/elasticsearch`):

```bash
LOCAL_VEC_BINARY_OS=linux ./gradlew -Druntime.java=25 -p benchmarks run \
  --args 'BenchmarkClassName.methodName -p param1=val1,val2 -p param2=val3'
```

Key notes:
- `LOCAL_VEC_BINARY_OS=linux` must be set so Gradle uses the locally built libvec
  instead of pulling the pre-built versioned one.
- `-Druntime.java=25` selects the JDK version.
- `-p benchmarks` tells Gradle to run from the `benchmarks` subproject.
- `--args '...'` passes JMH arguments:
  - First arg is a regex matching benchmark class/method (e.g. `VectorScorerOSQBenchmark.bulkScore`)
  - `-p paramName=value1,value2` sets JMH `@Param` values (comma-separated for multiple)
  - Other useful JMH flags: `-f <forks>`, `-wi <warmup iterations>`, `-i <measurement iterations>`,
    `-t <threads>`, `-rf json -rff results.json` (save results to file)

Example for the vertical D1Q4 PoC:
```bash
LOCAL_VEC_BINARY_OS=linux ./gradlew -Druntime.java=25 -p benchmarks run \
  --args 'VectorScorerOSQBenchmark.bulkScore \
    -p bits=1 \
    -p implementation=VERTICAL,VECTORIZED \
    -p directoryType=MMAP \
    -p dims=1024 \
    -p similarityFunction=DOT_PRODUCT \
    -p bulkSize=32'
```

### 5. (TBD - following steps to be documented as we go)
