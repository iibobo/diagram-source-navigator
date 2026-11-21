# Repository Guidelines

## Project Structure & Module Organization

Diagram Source Navigator connects local Mermaid class diagrams to Java declarations in IntelliJ IDEA.

- `src/main/java/dev/learning/navigator/`: plugin code. `OpenDiagramAction` opens diagrams, `NavigatorService` resolves PSI declarations, `NavigationServer` handles local HTTP, and `NavigationRequest` defines navigation inputs.
- `src/main/resources/`: browser bridge (`navigator-bridge.js`) and plugin registration (`META-INF/plugin.xml`).
- `src/test/java/dev/learning/navigator/NavigationServerTest.java`: HTTP and navigation-contract checks.
- `examples/diagram.html`: manual verification fixture.
- `scripts/build.py`: offline compiler and packager; generated artifacts belong in ignored `build/`.

## Build, Test, and Development Commands

Target IntelliJ IDEA 2026.2 (build 262) and JDK 25; the Python build requires Python 3.9+.

- `python3 scripts/build.py --test`: compile, run HTTP checks, and package the plugin ZIP under `build/distributions/` using a local IDEA installation.
- Add `--idea-home /path/to/idea --java-home /path/to/jdk-25` to override local SDK paths; `IDEA_HOME` and `JAVA_HOME` are also supported.
- `./gradlew check buildPlugin -PideaHome=/path/to/idea`: validate and package with the pinned Gradle wrapper.
- `./gradlew runIde -PideaHome=/path/to/idea`: launch an isolated IDEA sandbox. Gradle's first run requires dependency downloads.

## Coding Style & Naming Conventions

Match existing formatting: two-space indentation in Java, JavaScript, and Gradle Kotlin scripts; four spaces in Python. Use `UpperCamelCase` Java classes, `lowerCamelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep Java code in `dev.learning.navigator`. No formatter or linter is configured.

Keep PSI resolution in background smart-mode read actions and declaration opening on the UI thread. For releases, update `pluginVersion` in `gradle.properties`; both build paths inject it into the packaged descriptor.

## Testing Guidelines

HTTP tests use a standalone Java runner with real loopback HTTP and an injected navigator. Extend `NavigationServerTest` with descriptive `check(...)` assertions. Gradle runs PSI integration tests with the IntelliJ test framework. Run `node tests/browser/navigation.test.mjs` for real Chrome bridge tests; set `CHROME_BIN` for a custom executable. No numerical coverage threshold is configured.

For navigation or bridge changes, also follow README's IDEA acceptance checks: JDK/project/dependency declarations, overloads, inherited methods, diagram controls, and project shutdown. HTTP checks cannot validate PSI integration.

## Commit & Pull Request Guidelines

Use concise, imperative commit subjects. PRs should explain the behavior change, link relevant issues, report verification commands and manual results, and include screenshots for visible diagram changes.

## Security Boundaries

Preserve loopback binding, session-token authentication, Host/Origin validation, request limits, and static-resource containment. Keep tokens out of logs and exported reports; retain rejection checks for hidden files, source downloads, and escaping symlinks.
