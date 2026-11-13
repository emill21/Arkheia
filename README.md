# Arkheia

*ἀρχεῖα: Greek for "archives".* A file explorer for Wear OS: every file on your watch, on your wrist.

- **Browse** every folder, folders first, sorted by name, date, size or type. Hidden files on request.
- **Photos**: swipe through a folder's photos, crown or double-tap to zoom. **Gallery** shows them as thumbnails.
- **Videos**: full-screen player with a progress ring, 10-second skips and crown scrubbing.
- **Open** text files, play music,
  install APKs, or hand anything else to another app.
- **Manage**: copy, move, rename and delete files and folders; make new folders by voice or keyboard.
- **Storage**: free space at a glance, what's using it by type, and your 15 largest files.
- **Search** by name, **pin** folders to the home screen, and jump back to **recent** files.
- **Tile** with free space and shortcuts into All files and your first pinned folder.

## Install
1. Download `Arkheia.apk` from the latest [release](../../releases).
2. Install it on the watch: from your phone with **Syndesia** or *Wear Installer 2*, or from a computer with
   Wireless debugging on the watch (**Settings › Developer options › Wireless debugging**):
   `adb pair <ip:port>`, `adb connect <ip:port>`, `adb install Arkheia.apk`.
3. Open Arkheia and tap **Allow file access**, then **Allow** on the pop-up. That's the watch's own **Files**
   permission (also in App info › Permissions › Files), and it covers every file.

Every build is signed with the same key (`keystore/`), so updates install over the previous version and keep
your settings and access.

## Using it
- Swipe right to go back a folder. Tap a file for its details and actions; tap a folder to go in.
- At the bottom of every folder: **New folder**, **Sort**, **Pin to home**, and the folder's own actions.
- **Copy** or **Move** a file, open the destination folder, and tap **Paste here** at the top.
- **Phone** on the home dial browses your phone's files over Bluetooth, through [Syndesia](https://github.com/emill21/Syndesia) (install it on both the phone and the watch). Tap a file to copy it to `Download/From phone` on the watch.
- Android keeps some folders private (like `Android/data`), even from file managers.

## Build
GitHub Actions builds `Arkheia.apk` on every push and publishes a release for pushes to `main`.
Locally: `./gradlew :wear:assembleRelease`.
