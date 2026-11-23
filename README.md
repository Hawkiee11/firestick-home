# Family Hub for Fire TV

A simple home screen for a Fire TV Stick 4K Max (2nd gen, Fire OS 8). Few button presses for the things
used most.

- **Jump back in**: the last three apps you opened, whatever they are, newest first and already
  selected. One press of OK gets you back in.
- **Favourites**: up to five apps, in the order you choose.
- **Kids**: the apps in the Kids group. In the daytime (6am–7pm) this row sits above Favourites.
- **App drawer**: press down from the last row and every app slides up, grouped into Streaming, Kids,
  Music & audio, Games, Tools & settings, Amazon and Other (A–Z within each). Up from the top row, or
  Back, closes it.
- **Hold OK on any app** for a menu: Add to / Remove from Favourites, Move to front of Favourites,
  Move to group, Hide. "Show hidden" in the drawer's Tools group brings hidden apps back.
- Clock, date, a greeting (press OK on it to set the name) and the weather (Open-Meteo, no account). Press OK on the weather to set the place: type a town and pick it from the list.
- **Add app** in the top bar: type or paste a link to an .apk and Family Hub downloads and installs it (Fire OS asks first). No Downloader needed.
- **Update ready** appears in the top bar when a newer build is on GitHub: press OK to install it.

Every app tile shows the app's own 16:9 TV banner.

Fire OS won't let anything replace Amazon's home screen, so pin Family Hub to the front of Amazon's apps
row and it's one click from Home.

## First install (no computer)

1. Settings → My Fire TV → Developer Options → **Install unknown apps** → turn on for **Downloader**.
2. Open Downloader and enter:
   `https://github.com/Hawkiee11/firestick-home/releases/latest/download/firestick-home.apk`
   (the repo must be public for this link to work).
3. Install, then open **Family Hub** from Your Apps.
4. Pin it: in Your Apps, highlight Family Hub, press the ☰ (menu) button → **Move to front**.

## Updates

Once this version is installed, updates come from inside the app: **Update ready** shows in the top
bar within a few hours of a new build (or straight away when you open Family Hub). The first time, Fire
OS asks you to allow Family Hub to install apps (Developer Options → Install unknown apps → Family Hub).

## Optional: see apps opened from anywhere

Out of the box, Jump back in only knows about apps opened from Family Hub. To include apps opened any
other way (Alexa, Amazon's home), grant usage access once from a computer:

```bash
adb connect <stick-ip>:5555
adb shell appops set com.hawkiee.firestickhome GET_USAGE_STATS allow
```

## Build

GitHub Actions builds it (`.github/workflows/build.yml`) and publishes `firestick-home.apk` to the
`latest` release; the release text "Build N" is what the in-app updater compares against. Locally:
JDK 17, Android SDK, Gradle 8.9, `gradle :app:assembleRelease`.

`app/release.keystore` is committed on purpose, so every build can update the one before it. It signs
only this app.
