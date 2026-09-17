# Release checklist

1. Replace the support-email placeholder in `PRIVACY_POLICY.md`. Host the final policy at a public HTTPS URL and enter that URL in Play Console.
2. Decide on the final name and a unique package ID before first publication. The package ID cannot be changed for an existing Play listing.
3. Open the project in Android Studio and install SDK 35 and JDK 17 if prompted. Sync and run the app on an Android 10+ device.
4. Test 2, 3, and 12 photo flows; camera cancellation; gallery selection; portrait and landscape EXIF rotation; low-light and bright bracketed scenes; and saving/viewing the output. Check memory and processing time on a lower-end device.
5. In Android Studio, use **Build → Generate Signed Bundle / APK → Android App Bundle**. Create a private upload key, back it up securely, and record its passwords outside this folder. Upload the signed `.aab` to an internal test track first.
6. In Microsoft Edge, open [Google Play Console](https://play.google.com/console). Create the app, complete all mandatory store and policy forms, upload the 512 px icon and real screenshots, and submit the tested build for review.

Play Console requirements can change. Follow the current forms shown in your account. Publication requires a Play developer account and Google review; this folder contains preparation material, not a published release.
