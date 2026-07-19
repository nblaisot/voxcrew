# Play Store screenshots (VoxCrew)

Project config for the personal Cursor skill `android-play-screenshots`.

## Quick run

```bash
# French (default flow / device locale with values-fr)
./play-screenshots/run.sh

# English Play listing assets (*_US.png); does not delete FR shots
LOCALE=en-US ./play-screenshots/run.sh
```

Automation also accepts intent extras `enable_demo=true` and `locale=en-US`
(`MainActivity`), and `run.sh` sets per-app locales via
`adb shell cmd locale set-app-locales` after `pm clear`.

Outputs land in `play-screenshots/out/{phone,tablet7,tablet10}/` at **native** AVD
resolution (no crop/scale):

| Slot | AVD | Native size |
|------|-----|-------------|
| phone | `Play_Phone_1080x1920` | 1080×1920 |
| tablet7 | `My_7_inch_tablet` | 1920×1080 landscape |
| tablet10 | `Pixel_Tablet` | 2560×1600 landscape |

Keep tablet AVDs in landscape before capture (`wm size` width > height).

English files are named `01-profile-name_US.png` … `06-forget-quentin_US.png`.

## Compose testTags

| Tag | Screen |
|-----|--------|
| `profile_name_field` | Profile name field |
| `profile_continue` | Continuer button |
| `main_menu` | Hamburger menu |
| `main_audio_route` | Audio route icon |
| `main_vox_switch` | VOX toggle |
| `main_ptt` | PTT / VOX status button |
| `about_app_title` | About “VoxCrew” (demo easter egg) |
| `about_back` | About back |
| `crew_<name>` | Crew row (`crew_marc`, `crew_anne`, `crew_quentin`) |

## Demo mode

- **Humans:** À propos → tap **VoxCrew** title 5× (within 5s).
- **Automation:** `adb` / Maestro launch with extra `enable_demo=true` (see `flows/capture-all.yaml`).

Enabling demo also:

- Seeds Marc / Anne / Quentin
- Turns **VOX** on
- Selects **Nicolas' earbuds** (PTT shows the Bluetooth icon)

## Config

See `config.yaml` for AVDs, permissions, and shot list.

Phone AVD must be **Play-compliant natively** (this project uses `Play_Phone_1080x1920` at 1080×1920). Do not use tall Pixel skins (e.g. 1080×2424) and do not crop captures.
