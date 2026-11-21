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
| Real PSI integration | Passed | `NavigatorPsiTest`: 7 tests, zero failures/errors/skips; includes exact overloads, inheritance, actual editor opening, and not-found completion |
| Chrome DOM/network regression | Passed on the unchanged bridge | 12 real Chrome scenarios, including malformed mappings, deadlines, cancellation, keyboard, candidate choice, tab restoration and report download |
| Reproducible offline build | Passed historically | Two identical Python builds produced matching bytes; their earlier hash is not the current Gradle artifact hash |
| Archive contents | Passed | Both unsigned and signed candidates pass `scripts/check_distribution.py` |
| Author signing | Passed | RSA-4096 self-signed certificate; `signPlugin`, `verifyPluginSignature`, and `signedPluginChecksum` succeeded |
| Binary compatibility | In progress | Local Verifier resolved the current SDK but stalled downloading a transitive plugin; CI must verify the complete release matrix |
| Three-OS CI | Pending | Workflow must execute on the candidate commit; preserve actual run URLs and reports |
| Representative IDEA acceptance | Pending | Run the acceptance matrix below against the final candidate |
| Marketplace submission | Pending | Account has reached the upload form with vendor `iibobo`; upload only after technical gates pass |

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
