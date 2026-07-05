## What

## Why

## Testing

- [ ] `./gradlew build` passes
- [ ] `RIGHTSIZE_BACKEND=docker ./gradlew integrationTest` passes (if behavior-affecting)
- [ ] `RIGHTSIZE_BACKEND=microsandbox ./gradlew integrationTest` passes (if behavior-affecting)
- [ ] New/changed public API has KDoc
- [ ] No public renames/signature breaks (see CONTRIBUTING.md — names deliberately
      mirror Testcontainers), or this PR is a deliberate, discussed exception
