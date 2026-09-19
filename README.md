# LumaStack

An offline Android photo stacker. The user selects 2–12 photos, LumaStack automatically captures an exposure bracket inside its own camera screen (or imports existing shots), then aligns, blends, grades, and saves a JPEG to **Pictures/LumaStack**.

## Open and run

1. Install Android Studio with Android SDK 35, Gradle 8.9, and JDK 17.
2. Open this folder as a project. Let Gradle sync. If Android Studio asks for a Gradle distribution, select Gradle 8.9.
3. Run `legacyDebug` on Android 6–9 or `modernDebug` on Android 10+. Grant camera access for automatic capture. Android 6–9 also asks for storage access when saving the final JPEG.
4. For the best result, use a static scene and different exposure levels. Handheld movement is supported within the alignment range; moving subjects can ghost.

The app makes no API calls and has no network permission. It uses Camera2 for its built-in automatic bracket and the Android document picker for optional imports.

## Processing

Each source is oriented with EXIF, resized to at most 2048 pixels on the longest edge, aligned by a gradient comparison at preview resolution, then merged using exposure weights. The result receives a subtle tone curve and color grade. This is exposure fusion: it can retain detail from differently exposed inputs, but it cannot create missing highlight or shadow detail from identical exposures.

## Download

Download the APK matching your phone from the repository's **Releases** page: **Android 6–9** or **Android 10+**. Android may ask you to allow installation from the browser or Files app. Verify the APK checksum in the release assets.

## Release

See [publishing/GITHUB_RELEASE.md](publishing/GITHUB_RELEASE.md). The package ID is `com.lumastack.app`. For updates, retain the same signing key. Never commit or share the keystore or its passwords.

## Current verification

GitHub Actions builds both APK variants. The automated camera and processing flow still needs physical-device testing across different manufacturers.
