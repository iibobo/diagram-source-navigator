# Marketplace listing and demo media

## Description

`src/main/resources/META-INF/plugin.xml` supplies the description shown on
Marketplace and in IDEA's plugin details. Its opening section links to
https://github.com/iibobo/skills and explains the companion
`render-class-diagrams` skill. English comes before the Chinese introduction.
The skill produces source-mapped diagrams; the IDEA plugin opens declarations.
Compatible HTML from another generator remains supported.

## Top demo

The maintainer will supply the recording. No demo has been recorded or uploaded.
After the asset is supplied:

1. Review it for readable text and private information. Prefer a 1280 × 800 GIF
   with a short sequence: open the generated HTML through Tools, click a class,
   click a method, and show the actual IDEA declaration. MP4 can be converted
   into a GIF; retain the original recording separately.
2. Save the reviewed GIF as `docs/media/source-navigation-demo.gif` and place it
   directly below the README title. Do not add a broken placeholder image.
3. After the plugin listing exists, add the GIF to the first Media position in
   its Marketplace admin panel. Descriptor HTML alone does not configure the
   Marketplace Media gallery. Verify the resulting public page and IDEA listing.
4. Rebuild, sign and check the final candidate after descriptor/media edits.
   Current desktop packages predate these listing edits and remain unchanged.

The plugin has not yet been submitted to Marketplace; rollback acceptance is
still deferred. Preparing listing materials does not publish the plugin.

Official references:
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
