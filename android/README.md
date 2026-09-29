# Android app

The Android app watches the official TIDAL media session and publishes a Listening activity to the signed-in Discord app on the same phone. It targets Android 13+ and uses media-session callbacks rather than polling playback.

## Build inputs

Install Android Studio with Android SDK 36, NDK `27.0.12077973`, and CMake `3.22.1`. Download Discord Social SDK **1.10.18247 or newer** from the Discord Developer Portal for the Discord application used by the Windows app. Place its release `discord_partner_sdk.aar` at `app/libs/discord_partner_sdk.aar` (from this checkout's `../discord_social_sdk/lib/release/` if present). The SDK binary is intentionally excluded from Git.

Register `com.mousetz.tidalrpc://oauth/callback` as a redirect URI for your TIDAL developer application. The repository includes the public TIDAL client ID. To use another application, override it with `-PtidalClientId=YOUR_CLIENT_ID`. Never put a client secret in the APK. Build with:

```sh
./gradlew :app:assembleDebug
```

The installable APK is `app/build/outputs/apk/debug/app-debug.apk`. The app still sends title, artist, and timing without TIDAL sign-in; sign-in enables verified track links and catalog artwork. Upload an image named `hightide_x1024` to the Discord application's Rich Presence assets for the fallback icon.

## Phone setup

Install the official TIDAL and Discord apps and sign in to Discord. Open TIDAL RPC and grant Notification access so Android can expose TIDAL's media session. On some sideloaded Android 13+ devices, first open **App info → More → Allow restricted settings**. Sign in to TIDAL from TIDAL RPC for artwork and links.

The app clears presence when TIDAL pauses or stops, notification access is revoked, or Rich Presence is disabled. Android may stop third-party background processes on some devices; screen-off and reboot reliability must be checked on a physical phone. A foreground service is the agreed fallback if the listener alone proves insufficient.

Check on a phone: start and pause playback, skip and seek, change playback speed, force-stop and reopen Discord, revoke and regrant Notification access, switch accounts, then repeat with the screen off and after reboot. Verify presence clears on pause and does not show the wrong song after rapid skips. If the device kills the listener during screen-off use, report the device model and Android version before adding a foreground service.

## Google Play later

The project already targets API 36. For a Play release, configure your own signing key, build an AAB with `:app:bundleRelease`, provide a privacy policy describing Notification access, and review the current Play permission requirements. Do not commit the signing key, TIDAL tokens, or Discord SDK binary.
