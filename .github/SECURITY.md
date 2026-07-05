# Security Policy

## Supported versions

rightsize has not yet made a tagged release; security fixes land on `main`. Once
tagged releases begin, this section will list which minor version lines receive
security patches.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for suspected security
vulnerabilities. Instead, report privately:

- If GitHub's private vulnerability reporting is enabled on this repository, use
  **Security > Report a vulnerability** in the GitHub UI.
- Otherwise, email the maintainer (see the `developers` entry in the published
  POM metadata, or the repository's commit history) with a description of the
  issue, affected version(s)/commit, and reproduction steps if available.

Please include:

- Which backend(s) are affected (Docker, microsandbox, or both).
- Whether the issue requires a specific platform (e.g. Linux + KVM, Apple
  Silicon) to reproduce.
- Any relevant logs — but redact anything sensitive from your own environment.

We'll acknowledge reports as promptly as we can and follow up with a plan once
the issue is confirmed. Coordinated disclosure is appreciated; please give us a
reasonable window to ship a fix before public disclosure.

## Scope notes

rightsize provisions and runs external runtimes (`msb`, and the Docker daemon via
`docker-java`) on the developer's machine to support integration testing. Reports
involving the security posture of those runtimes themselves (rather than how
rightsize invokes them) should generally go to their respective upstream
projects, but please report to us as well if you're unsure — we'd rather
triage a report that turns out to be upstream than have it go unreported.
