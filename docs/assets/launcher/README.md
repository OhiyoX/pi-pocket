# Pocket Pi — Material 3 launcher artwork

Android launcher sources for the local Pi agent. The terminal prompt inside a softly tilted pocket-sized terminal distinguishes this from a generic assistant icon. Colors use a fixed Material 3 purple family; the Android themed-icon path is **alpha-only**, so the launcher can recolor it to the user's wallpaper palette.

- Source artwork: `ic_launcher_{background,foreground,monochrome}.svg` (108 × 108, central 72 × 72 safe area).
- Runtime: `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml` (Android VectorDrawable; no PNG encoding/build step).
- Android 8.0+ launcher: `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` use Adaptive Icon background/foreground and Android 13+ themed monochrome layer.
- Legacy launcher: `mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml` are fully composed VectorDrawables. Existing raster mipmaps remain as a safety fallback.

Design QA: check adaptive circular and rounded-square masks (central safe zone), monochrome Android 13+ launcher coloring (holes in display/keyboard), and both light/dark home screens. In-app icons are out of scope.

Validation: parse XML/SVG; run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and record results. Install and compare masked launcher renders on a device before shipping. Do not claim build/device QA passed when the execution environment is absent.
