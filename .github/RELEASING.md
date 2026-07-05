# Releasing rightsize

rightsize is not yet published anywhere public. This document is the checklist for
getting it there, split into what can happen now and what needs a live GitHub
remote first.

## Steps that need nothing extra (can happen at any time)

1. Confirm `./gradlew build` is green and `./gradlew publishToMavenLocal` produces
   POMs with correct metadata (name/description/url/license/developers/scm — see
   the shared `applyRightsizePom` helper in the root `build.gradle.kts`).
2. Bump `version` in the root `build.gradle.kts` from `0.1.0-SNAPSHOT` to the
   release version (e.g. `0.1.0`) and move the CHANGELOG's `Unreleased` section
   under a dated release heading.
3. Tag the release (`git tag v0.1.0`) once the repository has a remote to push
   the tag to.

## Steps that need the repository to exist on GitHub

The repository URL is `https://github.com/ngriaznov/rightsize-kotlin` (root
`build.gradle.kts` POM metadata, README links). Once it is pushed:

1. **Decide an Actions pinning policy**: the workflow currently uses `@v4`-style
   tags for `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`,
   and `actions/cache`. Pin to commit SHAs if the project wants supply-chain
   hardening; otherwise keep `@v4` and accept the (low) risk of a tag being
   repointed upstream.
4. **Add a coverage badge** once JaCoCo XML reports are uploaded somewhere
   badge-generating (e.g. Codecov) — the `core` module already produces
   `build/reports/jacoco/test/jacocoTestReport.xml` locally.
5. **Consider a `RIGHTSIZE_BACKEND` matrix axis** instead of the current
   duplicated `msb-linux` / `msb-macos` / `docker-fallback` jobs in
   `.github/workflows/ci.yml`, if the job count becomes a maintenance burden.
6. **Publish to a shared Maven repository** (Maven Central via Central Portal, or
   an interim GitHub Packages / private repo) — requires GPG signing
   (`signing` plugin) and Central Portal or Sonatype credentials, neither of which
   are wired up yet. Not required for `mavenLocal()`-based consumption, which is
   the documented path until this step happens.
7. **Add issue/PR templates, `SECURITY.md`, and a `CODEOWNERS` file** — repo
   hygiene that only matters once the GitHub repo accepts external contributions.

## Non-goals for now

- No Sonatype/Central Portal account exists yet; don't attempt an actual publish
  outside `mavenLocal()` until step 6 above is deliberately picked up.
- Badges and CI links are placeholders/absent until the repository has a public
  remote — don't add broken badge URLs pointing at a repo that doesn't exist yet.
