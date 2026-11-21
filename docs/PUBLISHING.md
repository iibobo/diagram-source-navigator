# Publishing and enterprise deployment

Publisher: **iibobo** · **iibobo@zohomail.cn** ·
[source and issues](https://github.com/iibobo/diagram-source-navigator).
License: Apache-2.0. Plugin ID: `dev.learning.diagram-source-navigator`.
The ID must remain stable after the first public release.

## Release gate

Version `0.3.0` is prepared as a release candidate. Before calling it a production
release, retain evidence for all of the following:

- The macOS/Windows CI matrix passes real HTTP, browser, and PSI tests. A configured
  workflow is not evidence that it has run; record the candidate commit and actual run URL.
- Plugin Verifier passes against the supported IDEA build. The configured target
  defaults to the current SDK locally. CI verifies every stable 2026.2 release
  available on 2026-11-21 (2026.2 through 2026.2.5, including 2026.2.0.1).
  The minimum descriptor build is 262.8665.258; later releases need fresh verification.
- Manual IDEA acceptance passes project/JDK/dependency navigation, sources absent
  or attached, overloads, inherited methods, indexing, project close, and reopening
  diagrams. Verify layout, zoom, tabs, full screen, keyboard operation, and the
  navigation audit report with representative enterprise diagrams.
- Check the archive with `scripts/check_distribution.py`, verify its checksum,
  review dependency notices, then sign the final artifact. Keep the exact signed
  ZIP and its checksum; signing changes archive bytes.
- Record actual outcomes and remaining limitations in `docs/VALIDATION.md`.
  A missing SDK/dependency, failed test, or unavailable verifier keeps that gate open.
- Verify that the source repository and vendor/support URLs are publicly reachable
  before submitting the open-source Marketplace listing.

## Build and verify

Use JDK 25. `gradle.properties` owns `pluginVersion`, the Java release, and the
pinned IDEA version. Update the changelog and descriptor change notes for releases.

```bash
./gradlew check buildPlugin pluginChecksum verifyPlugin
node tests/browser/navigation.test.mjs
python3 scripts/check_distribution.py build/distributions/diagram-source-navigator-0.3.0.zip
```

On Windows use `gradlew.bat` in PowerShell/cmd. `-PideaHome=/path/to/idea` uses a
local SDK. For automatic IDE sync, set `ideaHome` in Git-ignored `local.properties`;
the command-line property takes precedence. With neither setting (for example in
CI), Gradle downloads the pinned IDEA installer. The wrapper
distribution and wrapper JAR are checksum-verified. Gradle's first build needs
network access for build/test tools; the Python path needs only an installed SDK:

```bash
python3 scripts/build.py --idea-home /path/to/idea --java-home /path/to/jdk-25 --test
python3 scripts/check_distribution.py build/distributions/diagram-source-navigator-0.3.0.zip
```

The Python path runs packaged-JAR HTTP tests, not PSI tests or Plugin Verifier.
It writes `build/build-info.json` and `build/distributions/*.zip.sha256`.
Archive timestamps default to 1980-01-01 UTC; `SOURCE_DATE_EPOCH` can override
them within ZIP's supported range. Byte-for-byte reproduction requires identical
source, SDK/compiler, Python, and compression implementation. Python and Gradle
archives are independently valid and need not have identical hashes.

## Signing

Follow [JetBrains' signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
to create a private key and certificate. Keep them outside this repository.
Load `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and optionally `PRIVATE_KEY_PASSWORD`
through a local secret manager or CI secrets. Alternatively, set
`SIGNING_PRIVATE_KEY_FILE` and `SIGNING_CERTIFICATE_FILE` to files outside the repository.
The generated local key uses RSA-4096 and restrictive filesystem permissions.
Only its public certificate is tracked at `docs/release-signing.crt`.
The certificate expires on 2027-11-21; renew it before that date and retain a secure
backup of the private key. Then run:

```bash
./gradlew check verifyPlugin signedPluginChecksum
```

The Gradle signing task only runs when signing material is supplied. Confirm a
signed archive and its `.zip.sha256` sidecar were actually produced before uploading; an unsigned build passing
tests is not a signed release. Hash the exact signed ZIP with `sha256sum` (Linux),
`shasum -a 256` (macOS), or `Get-FileHash -Algorithm SHA256` (PowerShell).

## First Marketplace upload

The first upload must be manual. Sign in to [JetBrains Marketplace](https://plugins.jetbrains.com/),
create/complete the Vendor profile, accept its developer agreement, and upload
the reviewed signed ZIP. Use the English description first, add useful tags and
screenshots, select Apache-2.0 with the public source URL, and publish through the
default channel with the hidden flag off when ready. Confirm that the desired
plugin name and ID are available; local metadata cannot establish uniqueness.
Respond to review findings before announcing availability.

After approval, users of a compatible IDEA version can find **Diagram Source
Navigator** under Settings → Plugins → Marketplace. New versions can be uploaded
manually. Automated submission is an explicit maintainer action:

```bash
# Requires PUBLISH_TOKEN and signing environment variables, supplied securely.
./gradlew publishPlugin
```

`publishPlugin` depends on checks, verification, and signing. It is not invoked by
the verification workflow. Never commit tokens, private keys, or raw environment
dumps. See [official publishing instructions](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
and [upload requirements](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html).

## Enterprise offline rollout

Distribute the exact reviewed ZIP and checksum through the organization's normal
artifact channel. Install with Settings → Plugins → Install Plugin from Disk,
verify the supported IDEA version and Java plugin, and restart if prompted.
The included example is offline; user HTML and its dependencies require their
own review. Navigation audit reports contain declaration paths, so treat them as
internal diagnostic data. To roll back, uninstall or install a previously reviewed
compatible version from disk; reopen the diagram after restarting the IDE.

For a single compatibility target, run `./gradlew verifyPlugin -PverificationVersion=2026.2`.
The standard local task checks the current SDK; CI owns the full release matrix.
