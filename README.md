# Siding

Siding is a parked browser for a phone or a car screen. The shelf opens YouTube, Netflix, Prime Video, Disney+, JioHotstar, Sony LIV, Zee5, and Crunchyroll. Any other `http` or `https` site can be typed, spoken, or shared in. Playback uses the system WebView, including its normal Widevine support, and it stops when the app is paused or the car reports driving restrictions.

## What Siding includes

- Eight editable shelf shortcuts, bookmarks, history, and up to eight tabs
- Address bar, search (Google, DuckDuckGo, or Bing), and a spoken search
- Back, forward, reload, home, find on page, and share
- Desktop layout that only removes the `Mobile` token. The WebView token stays in place
- Fullscreen video, downloads into the Downloads folder, and file upload
- Per-site prompts for microphone, camera, and location
- Protected-media permission so a site can try normal DRM. The page still decides whether software DRM is acceptable
- Display size, light, dark, and true-black themes, optional page darkening
- Startup choice: shelf, home page, or last tabs
- Cookies for sign-in. Passwords and payment autofill are turned off
- Pause and mute when the activity pauses, when car UX restrictions require distraction optimization, and when a headset or media button asks to play during that pause

## What will not play

YouTube’s mobile site often plays. Netflix and most other streaming apps frequently refuse a WebView, require their own app, or require hardware DRM (Widevine L1) that WebView does not have. Siding does not strip the WebView identity, does not impersonate a TV or another browser, and does not change DRM robustness to get around that.

Google’s current parked-app rules also matter:

- Browsers are a beta category on Android Automotive OS, and they are not a supported Android Auto category
- Video apps are for Android Automotive OS, not phone-projected Android Auto
- Games are the only parked category Android Auto lists, and only while parked

Siding declares `APP_BROWSER` and `CAR_LAUNCHER`, and it does not set `distractionOptimized`. On Android Automotive OS the system is expected to block the activity while the car is moving. On phone Android Auto, a browser may not appear in the launcher at all. That is a platform limit, not a switch hidden in settings.

## Run it

Requirements: JDK 17+, Android SDK 35, an Android phone or an Android Automotive OS emulator.

```bash
# local.properties
sdk.dir=/path/to/android-sdk

./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`, then open Siding from the phone launcher. On Android Automotive OS it is a car-launcher browser and should only be usable while parked. Sign in on each service yourself. Clear cookies from Settings before someone else uses the car.

Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Safety

Siding is for passengers and for parked time. If you are driving, leave it closed. The app pauses media when it loses the foreground and when the car says the UI must be distraction-optimized. It does not read vehicle speed or gear, and it does not try to stay on the head unit while driving.
