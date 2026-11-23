# Marketplace listing and demo media

## Description

`src/main/resources/META-INF/plugin.xml` supplies the description shown on
Marketplace and in IDEA's plugin details. Its opening section links to
https://github.com/iibobo/skills and explains the companion
`render-class-diagrams` skill. English comes before the Chinese introduction.
The skill produces source-mapped diagrams; the IDEA plugin opens declarations.
Compatible HTML from another generator remains supported.

## README navigation screenshots

README displays `docs/media/marketplace/class-diagram-guided.png`,
`docs/media/marketplace/sequences-guided.png`, and
`docs/media/marketplace/flowcharts-guided.png` in its source-navigation demo,
in that order. The maintainer supplied all three annotated screenshots.

## Marketplace listing

https://plugins.jetbrains.com/plugin/36358-diagram-source-navigator

Manage listing screenshots separately through the Marketplace Media section.
Rebuild and sign the candidate after plugin descriptor edits.

Official references:
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
