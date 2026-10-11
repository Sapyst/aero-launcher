# Aero Launcher

A terminal-style Android home screen: monochrome, monospace, thin outlined boxes with the label on
the border, OLED black, and an ASCII statue drawn by the launcher itself. It mirrors the look and
the widgets of the *Arch Aero OS* desktop (Hyprland) it was made alongside.

![screenshots](docs/screenshots.png)

**[Download the APK](../../releases/latest)** (Android 8+, made for a Pixel 10 Pro XL).

## Features
- **Widgets**, chosen in the launcher's own settings (order, half/full width):
  - `clock`: hours bright, minutes dim, seconds, numeric date, São Paulo and local time
  - `weather`: now + 5 days with a temperature-range bar (Open-Meteo, location from the IP)
  - `apps`: pinned apps · `battery`: level, temperature, charger
  - `resources`: rings for cpu clock, memory, storage, heat + live network curve
  - `fetch`: fastfetch of the phone, read live from Android
  - `terminal`: launcher commands (`open`, `web`, `fetch`, `apps`, `clear`) + Android's shell (toybox, inside the app sandbox)
  - **any Android app widget** (music, calendar, notes…), hosted inside a box
- **Background**: the ASCII statue on true black (pixels off on OLED), with an optional subtle
  "live terminal" shimmer, or the system wallpaper.
- **Gestures**: drag down = notifications, drag up = search. **Long press a widget and drag it** to move it
  (the list scrolls by itself at the edges); long press and let go = its menu; long press on empty space = launcher menu.
- **Search**: live filter (`ffx` finds Firefox), Enter opens the first match, `web <text>` or no
  match = your default browser with the engine you pick (Google, DuckDuckGo, Startpage, Bing).
- Long press an app: pin to home, app info, uninstall.
- **Theme**: follow the phone, dark (OLED black) or light. **Language**: English or Portuguese (pt-BR).
- Asks to become the default home screen (Android's own dialog); works with gesture navigation.

## Settings
Long press on an empty part of the home screen → `launcher settings`.

## Build
Kotlin + Jetpack Compose, no other libraries.
```
./gradlew :app:assembleRelease          # needs JDK 17 and the Android SDK (platform 35)
```
The release APK is signed with the builder's debug key: updates must come from the same machine.

## Notes
- The ASCII art is generated from a photo by `tools/ascii_grid.py`
  (`python3 tools/ascii_grid.py photo.jpg app/src/main/assets/eris.txt 100`).
- Techniques for the notification shade and the app list were studied in
  [Kvaesitso](https://github.com/MM2-0/Kvaesitso) (GPL-3.0); no code was copied.
- Font: JetBrains Mono NL Nerd Font, SIL Open Font License (`licenses/JetBrainsMono-OFL.txt`).

## License
MIT, see `LICENSE`.
