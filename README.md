# Family Hub for Fire TV

A simple home screen for a Fire TV Stick 4K Max (2nd gen, Fire OS 8). One screen, no scrolling for the
things used most:

- **Jump back in**: the last three apps you opened, whatever they are, newest first and already
  selected. One press of OK gets you back to it.
- **Favourites**: Netflix, NOW and YouTube, always in the same place.
- **Kids**: CBeebies, YouTube Kids, BBC iPlayer and Disney+, whichever are installed.
- **App drawer**: press down from the last row and every app slides up, grouped into Streaming, Kids, Music & audio, Games, Tools & settings, Amazon and Other (A–Z within each). Up from the top row, or Back, closes it. Hold OK on an app to hide it; "Show hidden" at the end brings them back.
- Clock, date, a greeting and the weather for Stratford-upon-Avon (Open-Meteo, no account).

It is an ordinary app: Fire OS won't let anything replace Amazon's home screen, so pin it to the front
of Amazon's apps row and it's one click from Home.

## Install on the Fire Stick (no computer)

1. Settings → My Fire TV → Developer Options → **Install unknown apps** → turn on for **Downloader**.
2. Open Downloader and enter:
   `https://github.com/Hawkiee11/firestick-home/releases/latest/download/firestick-home.apk`
   (the repo must be public for this link to work).
3. Install, then open **Family Hub** from Your Apps.
4. Pin it: in Your Apps, highlight Family Hub, press the ☰ (menu) button → **Move to front**.

Updates: every push to `main` builds a new APK into the same link. Download it again and install over
the top; your recents are kept.

## Optional: see apps opened from anywhere

Out of the box, Jump back in only knows about apps opened from Family Hub. To include apps opened any
other way (Alexa, Amazon's home), grant usage access once from a computer:

```bash
adb connect <stick-ip>:5555
adb shell appops set com.hawkiee.firestickhome GET_USAGE_STATS allow
```

## Build

GitHub Actions builds it (`.github/workflows/build.yml`). Locally: JDK 17, Android SDK, Gradle 8.9,
`gradle :app:assembleRelease`.

`app/release.keystore` is committed on purpose, so every build can update the one before it. It signs
only this app.
