# Releasing rightsize

Releases go to Maven Central through the [Central Portal](https://central.sonatype.com/)
under the verified `dev.rightsize` namespace. Publishing is wired into the build:
`maven-publish` + `signing` in the root `build.gradle.kts`, and the
`com.gradleup.nmcp.settings` plugin in `settings.gradle.kts` drives the portal upload.

## One-time setup (per machine that publishes)

1. **Central Portal token** — log in at central.sonatype.com, generate a user token
   (Account → Generate User Token). It yields a token username and password.
2. **GPG signing key** — generate and publish one (skip if the key already exists):

   ```sh
   gpg --quick-generate-key "Mykyta Hriaznov <ngriaznov@users.noreply.github.com>" ed25519 sign never
   gpg --keyserver keys.openpgp.org --send-keys <KEYID>
   gpg --armor --export-secret-keys <KEYID> > rightsize-signing.asc   # keep private
   ```

   Central verifies signatures against public keyservers, so the send-keys step is
   required before the first upload validates.
3. **Environment** — the build reads four variables, all release-only (regular
   builds and `publishToMavenLocal` never need them):

   | Variable | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | portal token username |
   | `CENTRAL_TOKEN_PASSWORD` | portal token password |
   | `RIGHTSIZE_GPG_KEY` | ASCII-armored private key (the file contents, not a path) |
   | `RIGHTSIZE_GPG_PASSPHRASE` | the key's passphrase |

## Per release

1. Confirm `main` is green in CI, including `msb-windows`.
2. Set `version` in the root `build.gradle.kts` from `X.Y.Z-SNAPSHOT` to `X.Y.Z`,
   and update the version in the README/docs dependency snippets.
3. Move the CHANGELOG's `Unreleased` content under a dated `## [X.Y.Z]` heading.
4. Sanity-check locally:

   ```sh
   ./gradlew build publishToMavenLocal
   ```

   Every library module must produce main + sources + javadoc jars (plus core's
   test-fixtures jar); the BOM is pom-only. With the GPG variables set, each
   artifact also gets an `.asc` signature.
5. Upload the staged deployment:

   ```sh
   ./gradlew publishAggregationToCentralPortal
   ```

   `publishingType` is `USER_MANAGED`: the upload validates and then waits in the
   portal UI. Nothing is public yet.
6. Review the deployment at central.sonatype.com → Publish, then release it.
   A bad upload can be dropped there instead — nothing on Central is ever
   deletable after release, so this click is the point of no return.
7. Commit the version/CHANGELOG changes, tag, and push:

   ```sh
   git tag vX.Y.Z && git push origin main vX.Y.Z
   ```

8. Set `version` back to the next `-SNAPSHOT` and commit.
9. Artifacts appear on Maven Central within ~30 minutes of release; search
   indexing (central.sonatype.com/search) can lag a few hours behind.

## Coordinates

Consumers depend on `dev.rightsize:bom` as a platform and pick modules from it:

```kotlin
testImplementation(platform("dev.rightsize:bom:X.Y.Z"))
testImplementation("dev.rightsize:core")
testImplementation("dev.rightsize:modules")
testRuntimeOnly("dev.rightsize:backend-microsandbox")
testRuntimeOnly("dev.rightsize:backend-docker")
```
