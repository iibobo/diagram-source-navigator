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

Marketplace listing: https://plugins.jetbrains.com/plugin/36358-diagram-source-navigator

On 2026-11-21, four normalized PNG images were saved to its Media section in
order 1–4. They are stored under `docs/media/marketplace/`, all 1600 × 1000
(16:10), with English headings, proportional scaling and neutral padding.
Original screenshots remain unchanged for README. The edit page was refreshed
and four persisted screenshot items were confirmed. README/descriptor edits
alone do not upload Marketplace media.
The first screenshot shows the Installed tab, not evidence of public Marketplace
availability. The recording work is deferred in favor of the supplied screenshots.
Rebuild and sign the candidate after descriptor edits; current desktop packages
predate the companion-skill description edits and remain unchanged.

The Marketplace edit page reports the plugin was sent for moderation. Screenshot
saving is confirmed; review approval and public discoverability are not claimed.

Official references:
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
