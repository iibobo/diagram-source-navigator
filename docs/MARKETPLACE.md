# Marketplace listing and demo media

## Description

`src/main/resources/META-INF/plugin.xml` supplies the description shown on
Marketplace and in IDEA's plugin details. Its opening section links to
https://github.com/iibobo/skills and explains the companion
`render-class-diagrams` skill. English comes before the Chinese introduction.
The skill produces source-mapped diagrams; the IDEA plugin opens declarations.
Compatible HTML from another generator remains supported.

## Ordered homepage screenshots

The maintainer supplied four annotated PNG screenshots. README displays all four
near the top in their original order; images are copied without edits.

| Order | Asset under `docs/media/` | Caption |
| --- | --- | --- |
| 1 | `01-install-plugin.png` | Install and enable Diagram Source Navigator |
| 2 | `02-open-diagram.png` | Open HTML through the IDEA Tools action |
| 3 | `03-add-skills-marketplace.png` | Add the companion skills marketplace |
| 4 | `04-generate-diagram.png` | Invoke the class-diagram skill |

When the Marketplace listing exists, upload these four assets to its Media
section in order 1–4. README and descriptor edits do not upload Marketplace media.
The first screenshot shows the Installed tab, not evidence of public Marketplace
availability. The recording work is deferred in favor of the supplied screenshots.
Rebuild and sign the candidate after descriptor edits; current desktop packages
predate the companion-skill description edits and remain unchanged.

The plugin has not yet been submitted to Marketplace; rollback acceptance is
still deferred. Preparing listing materials does not publish the plugin.

Official references:
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
