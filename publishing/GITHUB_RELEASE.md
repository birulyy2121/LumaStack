# GitHub APK release

Create a **public** repository named `LumaStack` and push the contents of this folder. The included GitHub Actions workflow checks that a debug APK can build. Its Actions artifact is useful for testing but expires.

To make a publicly downloadable **preview**, push a tag such as `preview-v0.1.0`. The workflow builds a debug-signed APK and creates a prerelease with `LumaStack-preview.apk` and `SHA256SUMS.txt`. The release asset gives visitors a direct download. This preview is not device-tested, and a future release-signed APK may require users to uninstall the preview before installing it.

For a public release, build and test a **release-signed** APK with a persistent private signing key, create a GitHub Release tagged `v1.0.0`, and upload the APK as a release asset. Include its SHA-256 checksum, supported Android version (10+), and install instructions in the release notes. Keep the signing key and passwords securely backed up; future updates must use the same key.

Do not claim a production release until the capture/import/export flow has been tested on an Android device. GitHub does not deliver automatic app updates; users download new APKs manually. Some Android devices require the user to allow installs from the browser or Files app.
