# Release validation — 0.3.0

Release only after every required gate has passed for the candidate source and
artifact. Configured tasks and compiling test classes are not passing test results.

## Current evidence — 2026-11-21

Local environment: macOS, IDEA 2026.2.3 (`262.10968.63`), bundled JBR 25.0.4,
and project Gradle wrapper 9.2.1. The default terminal also has OpenJDK 25.0.2.

| Gate | Status | Evidence |
| --- | --- | --- |
| Gradle environment | Passed | Wrapper starts; test compilation, instrumentation and packaging pass, including an offline rerun |
| HTTP and security regression | Passed | Gradle `httpTest`: 325 real loopback HTTP checks; fixed the standalone runner's missing platform-library runtime classpath |
| Real PSI integration | Passed | `NavigatorPsiTest`: 8 tests, zero failures/errors/skips; includes exact overloads, inheritance, actual editor opening, not-found completion, and navigation resuming after indexing |
| Chrome DOM/network regression | Passed on the unchanged bridge | 12 real Chrome scenarios, including malformed mappings, deadlines, cancellation, keyboard, candidate choice, tab restoration and report download |
| Reproducible offline build | Passed | Two current Python builds match; repeated Gradle packaging retains the recorded unsigned SHA-256 |
| Archive contents | Passed | Both unsigned and signed candidates pass `scripts/check_distribution.py` |
| Author signing | Passed | RSA-4096 self-signed certificate; `signPlugin`, `verifyPluginSignature`, and `signedPluginChecksum` succeeded |
| Binary compatibility | Passed | CI run 44896157203: all seven declared IDEA versions passed; downloaded verdicts each say Compatible |
| macOS CI | Passed | Run 44896157203: PSI, HTTP/security, browser and archive checks all succeeded |
| Representative IDEA acceptance | Partially passed on macOS | Signed 0.3.0 installed and enabled after restart; synthetic project audit and individual editor checks passed (details below); rollback is deferred by the maintainer; automated offline core checks passed |
| Marketplace submission | Pending | Account has reached the upload form with vendor `iibobo`; upload only after technical gates pass |

The current release acceptance scope is macOS only, per the maintainer’s
2026-11-21 decision. Windows and Linux are deferred and are not release gates.

## Compatibility matrix

Minimum stable build: `262.8665.258` (IDEA 2026.2). Until-build: `262.*`.
JetBrains' official release API on 2026-11-21 lists these stable 2026.2 versions:
2026.2, 2026.2.0.1, 2026.2.1, 2026.2.2, 2026.2.3, 2026.2.4, 2026.2.5.
The CI Verifier matrix explicitly targets all seven. A later release requires new
verification evidence. Local `verifyPlugin` defaults to the current installed SDK;
`-PverificationVersion=2026.2` selects a specific distribution.

Source: https://data.services.jetbrains.com/products/releases?code=IIU&latest=false&type=release

## Current signed candidate

- Unsigned ZIP: `build/distributions/diagram-source-navigator-0.3.0.zip`
- Unsigned SHA-256: `b529c1767065c1ba4a305e0f8906093be6848f6ce105313d046a42b02b68bffe`
- Signed ZIP: `build/distributions/diagram-source-navigator-0.3.0-signed.zip`
- Signed SHA-256: `57563adb83acf3cd7ca09b673fe7f2b2029135dd65fc8faaa2eecb7797143bb8`
- Public certificate: `docs/release-signing.crt`; expires 2027-11-21.
- Certificate SHA-256 fingerprint: `CE:6F:3E:B2:60:06:6F:BA:D7:10:1C:2A:CB:82:A4:AB:30:B2:CD:8A:22:77:C4:E2:91:52:82:38:99:FD:1F:AF`.

The private key is outside Git in a directory with mode 0700 and file mode 0600.
Only the public certificate is tracked. Signed and unsigned ZIPs have different
hashes; record the exact uploaded signed file. No Marketplace token is required
for a first manual upload.

## Remaining acceptance

Use a separate IDEA sandbox and a representative project to check project/JDK
classes, dependency JARs with and without source attachments, overload ambiguity,
inherited declarations, indexing waits, diagram switching, project shutdown,
keyboard navigation, zoom/tabs/full screen, offline use, installation and rollback.
Keep project source fixtures synthetic; production audit reports may contain
internal paths. Selected HTML is trusted executable content and is not sandboxed.

Append actual CI run URLs, source commit, acceptance results, Verifier reports,
and Marketplace review status as each gate completes. A failed, skipped, or
unavailable required check remains a release blocker. Do not disable tests or
instrumentation, or lower Verifier failure levels, to make a gate green.

## Observed macOS IDEA acceptance — 2026-11-21

The signed candidate was installed into IDEA 2026.2.3 and shown as enabled with
version 0.3.0 and vendor iibobo after restarting. A separate synthetic Java 17
project was opened; existing user projects were preserved.

- Downloaded real navigation audit: `build/reports/manual/idea-navigation-audit.json`.
  Two diagrams contain 12 targets: 11 opened, and the intentionally unspecified
  `Worker.call(...)` returned the expected two overload candidates.
- Selecting `Worker#call(java.lang.String)` reported success and opened Worker.java.
  `Child#inherited()` opened its declaration in Parent.java.
- An attached-source JAR opened WithSources.java; a binary-only JAR opened
  BinaryOnly.class with IDEA's decompiler banner; JDK List opened List.java.
- Keyboard navigation, switching diagrams, entering/exiting full screen, and
  navigation after zoom were exercised successfully.
- Closing only the synthetic project preserved the existing user project. A
  navigation from the old paired browser page then failed with `Failed to fetch`,
  confirming that the disposed project's loopback server no longer served it.

The fixture has no external assets. This does not substitute for a complete
network-isolated installation/rollback or indexing-wait acceptance result.
The audit file is ignored because navigation reports contain local paths.

CI run for source `db277b6b57b90d0a64f90e988c10828d674c9f50`:
https://github.com/iibobo/diagram-source-navigator/actions/runs/44895517892
This run completed successfully: macOS tests and all seven compatibility jobs passed.

Additional local validation: `./gradlew --offline check buildPlugin pluginChecksum`
passed on 2026-11-21. Eight PSI tests have zero failures/errors/skips; HTTP checks
remain 325. This proves cached offline build operation, not network-isolated runtime.
Latest macOS-only CI: https://github.com/iibobo/diagram-source-navigator/actions/runs/44896157203

## Offline core evidence

`python3 scripts/verify_offline_macos.py` passed with a macOS process sandbox:
loopback connections succeeded, two external IP connections were rejected with
EPERM, and all 8 PSI and 325 HTTP checks passed under the inherited restriction.
Java uses IPv4 sockets for compatibility with the sandbox's loopback rule.
Reports: `build/reports/offline/network-policy.log` and `gradle.log`.

Chrome cannot start reliably inside the additional macOS sandbox. Browser
regression instead intercepts requests before network access and denies every
URL outside the loopback fixture origin. An explicit external-fetch probe fails,
and all 12 scenarios pass. This is a browser request restriction, separate from
the operating-system restriction verified for the Java core.

The maintainer deferred rollback on 2026-11-21. No downgrade has been installed;
0.3.0 remains installed. Rollback stays outstanding and submission remains blocked.

## Artifact correspondence

The signed ZIP remains the exact artifact listed above. Repeated Gradle packaging
produced unsigned SHA-256 `b529c1767065c1ba4a305e0f8906093be6848f6ce105313d046a42b02b68bffe`.
The CI unsigned ZIP has SHA-256 `3012222b1af472ac686bcb35d19f76cd1faac4b34dd02e6391fdc3bda42d2326`.
Every JAR entry, including every class and resource, matches the local signed
candidate except `META-INF/MANIFEST.MF`, whose build JVM/OS fields describe the
respective environments. Comparison: `build/reports/ci/candidate-comparison.json`.
The two Python packages match SHA-256
`b15b0a08e10d6d816d3eff44e2fe03f46f8776c9fe1397f66061f0fff8c66463`;
this is a separate build path and is not the uploaded signed ZIP.

Downloaded compatibility reports reside under
`build/reports/ci/artifacts/plugin-verifier-*/`. All seven verdicts are Compatible.
