# Repository Guidelines

## Project Structure & Module Organization

Diagram Source Navigator connects local Mermaid class diagrams to Java declarations in IntelliJ IDEA.

- `src/main/java/dev/learning/navigator/`: plugin code. `OpenDiagramAction` opens diagrams, `NavigatorService` resolves PSI declarations, `NavigationServer` handles local HTTP, `NavigationRequest` defines navigation inputs, and `NavigationTask` runs navigation asynchronously.
- `src/main/resources/`: browser bridge (`navigator-bridge.js`) and plugin registration (`META-INF/plugin.xml`).
- `src/test/java/dev/learning/navigator/NavigationServerTest.java`: HTTP and navigation-contract checks.
- `tests/browser/navigation.test.mjs`: real-Chrome bridge tests (`CHROME_BIN` overrides the executable).
- `examples/diagram.html`: manual verification fixture.
- `scripts/`: offline tooling — `build.py` (compiler/packager; artifacts go to ignored `build/`), `check_distribution.py` (validates ZIP contents before signing), `verify_offline_macos.py` (core checks with external network denied).
- `docs/`: release references — `PUBLISHING.md` (release gate and enterprise deployment), `VALIDATION.md` (recorded evidence; current platform acceptance is macOS only), `MARKETPLACE.md` (listing content), `release-signing.crt`.

## Build, Test, and Development Commands

Target IntelliJ IDEA 2026.2 (build 262) and JDK 25; the Python build requires Python 3.9+.

- `python3 scripts/build.py --test`: compile, run HTTP checks, and package the plugin ZIP under `build/distributions/` using a local IDEA installation.
- Add `--idea-home /path/to/idea --java-home /path/to/jdk-25` to override local SDK paths; `IDEA_HOME` and `JAVA_HOME` are also supported.
- `./gradlew check buildPlugin -PideaHome=/path/to/idea`: validate and package with the pinned Gradle wrapper.
- `./gradlew runIde -PideaHome=/path/to/idea`: launch an isolated IDEA sandbox. Gradle's first run requires dependency downloads.
- `python3 scripts/check_distribution.py <candidate.zip>`: validate packaged ZIP contents before signing or uploading.
- `python3 scripts/verify_offline_macos.py --idea-home /path/to/idea`: re-verify core Java/PSI behavior with external network denied.

## Coding Style & Naming Conventions

Match existing formatting: two-space indentation in Java, JavaScript, and Gradle Kotlin scripts; four spaces in Python. Use `UpperCamelCase` Java classes, `lowerCamelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep Java code in `dev.learning.navigator`. No formatter or linter is configured.

Keep PSI resolution in background smart-mode read actions and declaration opening on the UI thread. For releases, update `pluginVersion` in `gradle.properties`; both build paths inject it into the packaged descriptor.

## Testing Guidelines

HTTP tests use a standalone Java runner with real loopback HTTP and an injected navigator. Extend `NavigationServerTest` with descriptive `check(...)` assertions. Gradle runs PSI integration tests with the IntelliJ test framework. Run `node tests/browser/navigation.test.mjs` for real Chrome bridge tests; set `CHROME_BIN` for a custom executable. No numerical coverage threshold is configured.

For navigation or bridge changes, also follow README's IDEA acceptance checks: JDK/project/dependency declarations, overloads, inherited methods, diagram controls, and project shutdown. HTTP checks cannot validate PSI integration.

For release work, read `docs/PUBLISHING.md` first: its release gate requires real CI run URLs, Plugin Verifier results, manual acceptance evidence, a passing distribution check, and a signed artifact. Record actual outcomes and open gates in `docs/VALIDATION.md`; a configured-but-unrun check is not a pass. The plugin ID `dev.learning.diagram-source-navigator` must remain stable, and current platform acceptance covers macOS only (Windows/Linux deferred).

## Commit & Pull Request Guidelines

Use concise, imperative commit subjects. PRs should explain the behavior change, link relevant issues, report verification commands and manual results, and include screenshots for visible diagram changes.

## Security Boundaries

Preserve loopback binding, session-token authentication, Host/Origin validation, request limits, and static-resource containment. Keep tokens out of logs and exported reports; retain rejection checks for hidden files, source downloads, and escaping symlinks.
