# Release validation — 0.3.0

## Scope and decision

As agreed on 2026-11-21, current platform acceptance covers **macOS only**.
Windows and Linux are deferred. The release remains a candidate: the maintainer
has deferred rollback acceptance, and Marketplace submission has not occurred.
A configured check, skipped test, or generated ZIP is not evidence of a pass.

## Gates

| Gate | Status | Evidence |
| --- | --- | --- |
| Gradle environment | Passed | Wrapper 9.2.1, JDK 25; compilation, instrumentation, packaging and offline checks succeed |
| HTTP/security regression | Passed | 325 real loopback checks, locally and in macOS CI |
| PSI integration | Passed | 8 tests; zero failures/errors/skips, including actual editor opening, exact overloads, inherited declarations, not-found completion and resuming after indexing |
| Browser regression | Passed | 12 real Chrome scenarios; keyboard, candidate selection, mapping errors, cancellation, timeouts, tabs and report download |
| Offline core | Passed automatically | OS-restricted Java PSI/HTTP tests plus browser request interception, detailed below |
| Reproducible build | Passed | Repeated current Gradle packages retain the unsigned hash below; two Python builds also match each other |
| Package and author signature | Passed | Both candidates pass the distribution checker; RSA-4096 signing and signature verification passed |
| Binary compatibility | Passed | Run 44897419722: all seven declared IDEA versions passed; each downloaded verdict says Compatible |
| macOS CI including offline checks | Passed | Test job 136825938155, run 44897419722, including the new OS-restricted checks |
| Real IDEA acceptance | Passed except rollback | Signed 0.3.0 installation/restart, navigation, viewer controls and project shutdown verified below |
| Rollback | Deferred by maintainer | No downgrade installed; retain as an outstanding release gate |
| Marketplace submission | Pending | Vendor iibobo reached the upload form; submission waits for release gates |

## CI and compatibility evidence

- Initial complete pass: https://github.com/iibobo/diagram-source-navigator/actions/runs/44896157203
  — source `03e6d483698a263ddf30e088dc6c8a038ef6cfaa`.
- Latest verification: https://github.com/iibobo/diagram-source-navigator/actions/runs/44897419722
  — source `9f2cfaa798ff1532904d783cd604309b36f39f49`.
  This adds offline verification and browser test-driver hardening; production
  classes and resources are unchanged. All seven refreshed compatibility verdicts
  also say Compatible.

Declared range: minimum stable build `262.8665.258`, until `262.*`.
Verified matrix: 2026.2, 2026.2.0.1, 2026.2.1, 2026.2.2, 2026.2.3, 2026.2.4,
2026.2.5. Later releases need new evidence. JetBrains release listing:
https://data.services.jetbrains.com/products/releases?code=IIU&latest=false&type=release

Downloaded latest reports are under `build/reports/ci/artifacts/44897419722/`. `-PverificationVersion=2026.2` selects a specific target.
Local `verifyPlugin` defaults to the installed SDK. Local Verifier previously
stalled during dependency download; passing CI reports provide the evidence.

## Candidate identity

- Unsigned ZIP: `build/distributions/diagram-source-navigator-0.3.0.zip`
- Unsigned SHA-256: `b529c1767065c1ba4a305e0f8906093be6848f6ce105313d046a42b02b68bffe`
- Signed ZIP: `build/distributions/diagram-source-navigator-0.3.0-signed.zip`
- Signed SHA-256: `38f856037f2d05647b17634bccf1aa4fb1e73e6e798dc70896eb9fcec867c21c`
- Public certificate: `docs/release-signing.crt`; expires 2037-11-21.
- Certificate SHA-256: `8D:8D:CB:4D:2E:8F:14:CB:A8:06:D4:21:30:65:4F:78:6B:A9:9D:CF:12:F3:BA:4B:CC:AF:3F:6F:06:02:29:B2`.

The private key is outside Git with directory mode 0700 and file mode 0600.
Only the public certificate is tracked. Signed and unsigned ZIPs differ.

CI's unsigned ZIP hash is
`3012222b1af472ac686bcb35d19f76cd1faac4b34dd02e6391fdc3bda42d2326`.
All JAR classes/resources match the signed candidate; only the manifest's build
JVM/OS fields differ. Evidence: `build/reports/ci/latest-candidate-comparison.json`.
The separately built Python package hash is
`b15b0a08e10d6d816d3eff44e2fe03f46f8776c9fe1397f66061f0fff8c66463`;
this is not the release's signed artifact.

## Offline verification

`python3 scripts/verify_offline_macos.py` passed locally and in macOS CI.
Its inherited process sandbox allows loopback and local IPC; an explicit probe
confirms two external IP connections fail with EPERM. All 8 PSI and 325 HTTP
checks run again with dependency downloads disabled. Java uses IPv4 sockets to
match the sandbox's loopback filter. This does not change system network settings.

Chrome cannot start reliably inside the additional OS sandbox. Browser tests
instead intercept requests before network access and reject every URL outside
the fixture origin. An external-fetch probe is observed and rejected, all 12
scenarios pass, and unexpected external requests fail the suite. This browser
request restriction is separate from the Java core's OS-level restriction.
Reports are under `build/reports/offline/` and in CI artifacts.

## Observed real IDEA acceptance

Environment: macOS, IDEA 2026.2.3 (`262.10968.63`), JBR 25.0.4. The signed plugin
was installed, restarted and shown as enabled with version 0.3.0/vendor iibobo.
A separate synthetic Java 17 project preserved existing user projects.

- Two diagrams have 12 targets: 11 opened; unspecified `Worker.call(...)` returned
  two expected candidates. Selecting the String overload opened Worker.java.
- `Child#inherited()` opened Parent.java. A source-attached dependency opened
  WithSources.java; a binary-only dependency opened the decompiler. JDK List
  opened List.java.
- Keyboard navigation, diagram switching, full-screen entry/exit, and navigation
  after zoom worked. Fixtures contain no external assets.
- Closing only the fixture project preserved the user's existing project. The old
  paired browser page then failed to connect, confirming its server was disposed.

Audit: `build/reports/manual/idea-navigation-audit.json`. Reports contain local
paths and are ignored by Git. Selected HTML remains trusted executable content.
No downgrade or rollback test has been performed following the maintainer's
request to defer it. No Marketplace upload or review approval is claimed.

## Certificate renewal — 2026-11-21

At the maintainer’s request, a replacement self-signed certificate using the
existing private key now expires 2037-11-21 11:32:40 UTC. The candidate was signed
again and independently verified with Marketplace ZIP Signer 0.1.45. Its JAR is
identical to the unsigned package; prior PSI/browser/compatibility evidence
applies to the unchanged plugin payload. The installed copy and earlier evidence
archive used the old certificate; no reinstall or Marketplace upload is claimed.
