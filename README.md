# LumaStack

An offline Android photo stacker. The user selects 2–12 photos, LumaStack automatically captures an exposure bracket inside its own camera screen (or imports existing shots), then aligns, blends, grades, and saves a JPEG to **Pictures/LumaStack**. Its optional Liquid Glass appearance can be turned on or off inside the app.

Version 1.1 adds tap-to-focus, 3x/5x/8x/12x stack presets, automatic reference-frame selection, stronger exposure fusion with motion-aware ghost suppression, and an inspection view with pinch zoom, original/result comparison, processing statistics, and native sharing.

## Open and run

1. Install Android Studio with Android SDK 35, Gradle 8.9, and JDK 17.
2. Open this folder as a project. Let Gradle sync. If Android Studio asks for a Gradle distribution, select Gradle 8.9.
3. Run `legacyDebug` on Android 6–9 or `modernDebug` on Android 10+. Grant camera access for automatic capture. Android 6–9 also asks for storage access when saving the final JPEG.
4. Tap anywhere in the viewfinder to focus, choose a quick stack preset, and hold steady while the automatic bracket runs. For the best result, use a mostly static scene with different exposure levels.

The app makes no API calls and has no network permission. It uses Camera2 for its built-in automatic bracket, Android's modern multi-photo picker on Android 13+, and a compatible document picker on older phones. Gallery imports automatically match the stack depth to the number of selected photos.

## Processing

Each source is oriented with EXIF and safely resized to at most 2048 pixels on the longest edge. LumaStack selects the sharpest well-exposed reference, estimates translation with a coarse-to-fine gradient search, and combines frames using well-exposedness, saturation, and local-contrast weights. Color-distance confidence reduces moving-subject ghosts. The result receives highlight roll-off, a restrained filmic curve, and skin-aware saturation. This is exposure fusion: it can retain detail from differently exposed inputs, but it cannot create missing highlight or shadow detail from identical exposures.

## Download

Download the APK matching your phone from the repository's **Releases** page: **Android 6–9** or **Android 10+**. Android may ask you to allow installation from the browser or Files app. Verify the APK checksum in the release assets.

## Release

See [publishing/GITHUB_RELEASE.md](publishing/GITHUB_RELEASE.md). The package ID is `com.lumastack.app`. For updates, retain the same signing key. Never commit or share the keystore or its passwords.

## Current verification

GitHub Actions builds both APK variants. The automated camera and processing flow still needs physical-device testing across different manufacturers.
