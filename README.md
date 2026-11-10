# Arkheia

*ἀρχεῖα: Greek for "archives".* A file explorer for Wear OS: every file on your watch, on your wrist.

- **Browse** every folder, folders first, sorted by name, date, size or type. Hidden files on request.
- **Open** photos (turn the crown or double-tap to zoom, drag to pan), read text files, play music,
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
3. Open Arkheia and tap **Allow file access**. It opens the switch in your watch's own Settings: turn on
   *Allow access to manage all files*. If your watch has no such switch, tap **Just photos, videos & music**
   for the normal Allow pop-up instead.

Every build is signed with the same key (`keystore/`), so updates install over the previous version and keep
your settings and access.

## Using it
- Swipe right to go back a folder. Tap a file for its details and actions; tap a folder to go in.
- At the bottom of every folder: **New folder**, **Sort**, **Pin to home**, and the folder's own actions.
- **Copy** or **Move** a file, open the destination folder, and tap **Paste here** at the top.
- Android keeps some folders private (like `Android/data`), even from file managers.

## Build
GitHub Actions builds `Arkheia.apk` on every push and publishes a release for pushes to `main`.
Locally: `./gradlew :wear:assembleRelease`.
