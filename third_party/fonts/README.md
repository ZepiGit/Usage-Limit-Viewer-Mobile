# Bundled fonts

`manifest.json` pins the six SIL Open Font License 1.1 families. Paths are relative to this directory. Each `source` is a tagged upstream release, release archive, or raw file; `upstream_file` is the member path in that archive or tag. Each SHA-256 covers the original bytes, including the accompanying notices.

Android builds `android-res` as a resource directory. The same TTF and notice files are the source for the iOS app bundle; they are not widget assets. Neither app needs to download fonts. Android CI runs `python3 tools/check_font_bundle.py <apk>` for both APKs.

Only upright fonts used by the app are included: variable Roboto Flex and Figtree (450/560), variable Roboto Mono and JetBrains Mono (400/500/700), IBM Plex Sans Regular/Medium, and IBM Plex Mono Regular/Medium/Bold. Other cuts, web fonts, source files and tooling are not bundled. The Android system Roboto choice is not a bundled font.
