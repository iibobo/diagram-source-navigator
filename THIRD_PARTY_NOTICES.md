# Third-party components

The plugin archive contains this project's Java classes, browser bridge, metadata,
and original SVG icon. It does not redistribute the IntelliJ Platform SDK, Gson,
JetBrains Runtime, JUnit, Gradle distribution, or Mermaid runtime.

- IntelliJ Platform APIs, the Java plugin, and Gson are provided by the user's
  IDEA installation. Their licenses remain with that installation.
- `examples/diagram.html` uses an original static SVG fixture. It contains no
  Mermaid JavaScript, CDN references, or external fonts.
- The Gradle wrapper files are redistributed under Apache-2.0; see their embedded
  copyright/license notices, this repository's `LICENSE`, and the upstream source:
  https://github.com/gradle/gradle/tree/v9.2.1/gradle/wrapper.
- Build and test dependencies are downloaded separately; they are not included
  in the plugin ZIP. Their applicable licenses remain in their upstream packages.

User-selected HTML may contain third-party scripts or graphics. Users must have
permission to use those files; their licenses do not become Apache-2.0 by being
opened with this plugin. Review dependency notices again before bundling any new
third-party code, including an offline Mermaid renderer.
