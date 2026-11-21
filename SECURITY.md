# Security policy

Report vulnerabilities privately to **iibobo@zohomail.cn**. Include the affected
plugin/IDE version, reproduction steps, and a minimal sanitized fixture. Keep
session tokens, proprietary source, and credentials out of public issues. The
maintainer will investigate; this project does not promise a contractual SLA.

## Supported scope

The current release line targets IntelliJ IDEA 2026.2.x (build 262) with Java
support. Wider IDE versions, remote development, and unverified operating systems
are outside the current verified scope. See `docs/VALIDATION.md` for actual results.

## Trust boundaries

Navigation uses a project-scoped loopback HTTP service, a per-session bearer
token, Host/Origin checks, bounded requests, and expiring navigation tasks. Closing
the project or opening a new diagram invalidates the previous session. Tokens
remain in browser memory and must not appear in logs or exported reports.

Open only trusted HTML. The selected page executes in the user's external
browser and receives the navigation token; any scripts included by that page
share its authority. HTML may itself load external assets. The plugin does not
sandbox arbitrary JavaScript or certify that user-provided diagrams are offline.
It has no telemetry or cloud navigation service. For an offline enterprise
deployment, review the diagram and all its assets as well as the plugin archive.

Static resources are constrained to the selected HTML's real parent directory;
directory listings, hidden files, Java source downloads, and escaping symlinks
are rejected. The API navigates declarations and does not edit files or execute
commands. IDE project trust and dependency synchronization remain prerequisites.
