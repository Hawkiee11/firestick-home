# Family Hub for Fire TV

A simple home screen for a Fire TV Stick 4K Max (2nd gen, Fire OS 8). Few button presses for the things
used most.

- **Jump back in**: the last three apps you opened, whatever they are, newest first and already
  selected. One press of OK gets you back in.
- **Favourites**: up to five apps, in the order you choose.
- **Games**: the Games group (emulators such as RetroArch and Lemuroid count). Hidden when there are none.
- **Search** in the top bar: type part of an app's name, or search the Appstore.
- **Kids**: the apps in the Kids group. In the daytime (6am–7pm) this row sits above Favourites.
- **App drawer**: press down from the last row and every app slides up, grouped into Streaming, Kids,
  Music & audio, Games, Tools & settings, Amazon and Other (A–Z within each). Up from the top row, or
  Back, closes it. ⏪ and ⏩ jump a whole group at a time.
- **Hold OK (or press ☰) on any app** for a menu: Add to / Remove from Favourites, Move left / right / to front,
  Move to group, Uninstall (apps you installed), Hide. In All apps, a padlock marks Amazon's built-in apps, which can be hidden but not uninstalled. Hidden apps sit faded in a Hidden section at the bottom of All apps; OK on one gives Unhide, Open or Uninstall.
- Clock, date, a greeting (press OK on it to set the name) and the weather (Open-Meteo, no account). Press OK on the weather to set the place: type a town and pick it from the list.
- **Add an app** (in Settings): type or paste a link to an .apk and Family Hub downloads and installs it (Fire OS asks first). No Downloader needed.
- **Settings › Check for updates** checks straight away and shows the build you have.
- **Update ready** appears in the top bar when a newer build is on GitHub: press OK to install it.

- **Sleep** in the top bar: switch the TV off in 15 minutes to 2 hours. Fire OS won't show the approval
  screen on the TV, so it needs one command from a computer first:
  `adb shell dpm set-active-admin com.hawkiee.firestickhome/.SleepAdmin`. While that's on, Fire OS won't
  let Family Hub be uninstalled until it's switched off again
  (`adb shell dpm remove-active-admin com.hawkiee.firestickhome/.SleepAdmin`).
- A red warning in the top bar only when something's wrong: **No internet** or **Storage low** (under
  1 GB free). OK on it opens the matching settings page.
- Apps you installed in the last seven days get a **NEW** badge.

- **Settings** in the top bar opens Family Hub settings:
  - **Theme**: Navy, Midnight, Slate, Forest, Plum or Ember.
  - **Text size**: Normal or Large.
  - **Clock**: 24-hour or 12-hour.
  - **Wallpaper**: Plain, Soft glow, Gradient, or a photo from a link (shown under a dark veil so text
    stays readable).
  - **App pictures**: one list of every app; pick one, give it a picture from a link, and the list comes
    straight back for the next. "Reset all pictures" undoes them all. Also in each app's hold-OK menu.
  - **Kids time & PIN**: set the hours (they can cross midnight), a 4–8 digit PIN, and how long the PIN
    unlocks for. In kids time the Kids row comes first; with the PIN lock on, opening anything outside
    the Kids group, the hold-OK menus and settings need the PIN. Outside kids time nothing is locked.
    (Amazon's own home screen isn't covered, so it's a nudge, not a wall.)
  - Greeting name, Weather location, Add an app from a link, Restart the Fire Stick (shows the remote
    shortcut; an app can't restart the stick itself), Fire TV settings.
- In kids time the top bar shows **Kids time · locked**, or **Unlocked · 23m · Lock now** after the PIN;
  OK on it unlocks or locks straight away.

Every app tile shows the app's own 16:9 TV banner, or a picture you chose.

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

## One-time setup from a computer (ADB)

Connect once (accept the "Allow USB debugging?" prompt on the TV):

```
adb connect <stick-ip>:5555
```

**Open on Home and at start-up** (Settings › "Open on Home button" turns it off again):

```
adb shell settings put secure enabled_accessibility_services com.hawkiee.firestickhome/com.hawkiee.firestickhome.HomeWatcher
adb shell settings put secure accessibility_enabled 1
```

**Sleep timer:**

```
adb shell dpm set-active-admin com.hawkiee.firestickhome/.SleepAdmin
```

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
