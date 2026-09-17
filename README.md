# LumaStack

An offline Android photo stacker. The user selects 2–12 photos, captures that many with the device's camera app or chooses existing shots, then LumaStack aligns, blends, grades, and saves a JPEG to **Pictures/LumaStack**.

## Open and run

1. Install Android Studio with Android SDK 35, Gradle 8.9, and JDK 17.
2. Open this folder as a project. Let Gradle sync. If Android Studio asks for a Gradle distribution, select Gradle 8.9.
3. Run `app` on an Android 10+ device. The external camera app must support full-resolution `ACTION_IMAGE_CAPTURE` output.
4. For the best result, use a static scene and different exposure levels. Handheld movement is supported within the alignment range; moving subjects can ghost.

The app makes no API calls and has no network permission. It uses the Android camera intent and document picker rather than accessing the full photo library directly.

## Processing

Each source is oriented with EXIF, resized to at most 2048 pixels on the longest edge, aligned by a gradient comparison at preview resolution, then merged using exposure weights. The result receives a subtle tone curve and color grade. This is exposure fusion: it can retain detail from differently exposed inputs, but it cannot create missing highlight or shadow detail from identical exposures.

## Download

Once published on GitHub, download the APK from the repository's **Releases** page. Android may ask you to allow installation from the browser or Files app. Only download from the repository linked by the developer; verify the APK checksum in the release notes. A GitHub release is an APK download, not an automatic installer or Play Store update channel.

## Release

See [publishing/GITHUB_RELEASE.md](publishing/GITHUB_RELEASE.md). The package ID is `com.lumastack.app`. For updates, retain the same signing key. Never commit or share the keystore or its passwords.

## Current verification

The source and assets are prepared. No APK/AAB build or device test has been completed on this computer because Android SDK, Gradle, and JDK 17 are not installed here.
