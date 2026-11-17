# Voyager Panel

A small Android app that shows [Voyager UI](https://github.com/ozancs/voyager-ui) full screen,
for a tablet used as a printer panel. It is an independent wrapper, not part of the Voyager UI project:
Voyager itself is loaded from your printers, the app adds what a browser tab can't do well on a tablet.

## Install

1. Install Voyager UI on your printer(s) (it serves on port 1977).
2. Download the latest `VoyagerPanel-*.apk` from [Releases](../../releases) on the tablet and open it
   (Android asks once to allow installing apps from the browser or file manager).
3. On first start, add your printers: **Find printers on this network**, or type an address.

New versions install over the old one.

## What it does

- **Setup:** on first start, Settings opens. Type a printer's address (`192.168.1.50` is enough: Voyager's
  port 1977 is added) or press **Find printers on this network**, which looks for Voyager on the tablet's
  network. Several addresses can be listed, one per line, in order of preference.
- **Several printers:** every printer serves its own copy of Voyager. The app opens the first address that
  answers, so a printer that is switched off doesn't block the panel. If the open printer stops answering
  for about 30 seconds, the app moves to the next one that answers (can be turned off in Settings). Inside
  Voyager, its own printer menu switches between printers.
- Opens Voyager's **All printers** page (or the dashboard, in Settings).
- Full screen, keeps the screen on, landscape (each can be turned off in Settings).
- If no printer can be reached, shows a "can't reach" screen and keeps trying every 5 seconds.
- G-code uploads and restoring a settings backup use Android's file picker. Downloads go to the Downloads
  folder, including the files Voyager makes in the page itself (settings backup, several files as one zip,
  timelapse frames).
- The Back button goes back inside Voyager; with nothing to go back to it opens a menu
  (All printers, Open another printer, Reload, Settings, Exit).

Voyager itself is loaded from the printer, so Voyager updates need no new app build.

The settings screen is a page in the app (`app/src/main/assets/settings.html`) drawn in Voyager UI's style.
It uses the Onest and JetBrains Mono fonts (SIL Open Font License 1.1, licenses next to the fonts in
`app/src/main/assets/fonts/`).

## Building

Every push to `main` builds the APK with GitHub Actions (`.github/workflows/build.yml`) and publishes it as a
release. The APK is signed with a key kept in two repository secrets, never in the repository:

- `VOYAGER_PANEL_KEYSTORE`: the keystore (`.jks`), base64
- `VOYAGER_PANEL_KEYSTORE_PASSWORD`: its password (for the store and the key, alias `voyagerpanel`)

Without them (a fork, a pull request) the build uses Android's debug key and keeps the APK as a workflow
artifact only. To build locally: `gradle assembleRelease` (Gradle 8.11, JDK 17, Android SDK 35); set
`KEYSTORE_FILE` and `KEYSTORE_PASSWORD` to sign with your own key.

The app icon is a simple placeholder.

## License

Copyright (C) 2026 DubMonstaa

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General
Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
option) any later version. See [LICENSE](LICENSE). This is the same license as Voyager UI.
