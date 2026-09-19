# GitHub APK release

Create a **public** repository named `LumaStack` and push the contents of this folder. The included GitHub Actions workflow checks that a debug APK can build. Its Actions artifact is useful for testing but expires.

To make a publicly downloadable **preview**, push a tag such as `preview-v0.2.0`. The workflow builds two debug-signed APKs and creates a prerelease with `LumaStack-Android6-9.apk`, `LumaStack-Android10-plus.apk`, and `SHA256SUMS.txt`. These preview builds are not device-tested across every manufacturer, and a future release-signed APK may require users to uninstall the preview before installing it.

For a public release, build and test both **release-signed** variants with a persistent private signing key, create a GitHub Release, and upload the Android 6–9 and Android 10+ APKs. Include SHA-256 checksums and install instructions. Keep the signing key and passwords securely backed up; future updates must use the same key.

Do not claim a production release until the capture/import/export flow has been tested on an Android device. GitHub does not deliver automatic app updates; users download new APKs manually. Some Android devices require the user to allow installs from the browser or Files app.
