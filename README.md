# Android 17 (AOSP) for the Eewrite E-PAD

AOSP 17 (`android-17.0.0_r1`) running on the **Eewrite E-PAD** (Epad_PRO). That is a 10.3" E Ink tablet (1872×1404)
built on the MediaTek **MT6797** (Helio X20/X25), with an MT6351 PMIC, a T1000 E Ink controller, a TT21000 touch
panel and a HID pen.

The tablet ships with Android 7.1.1 on a vendor 3.18 kernel and has no Treble, so a GSI is not possible. This port
runs on a **mainline Linux 7.2** kernel with the E-PAD patch series, which is shared with the postmarketOS port
([epad-pmos](https://github.com/bluelightsaber/epad-pmos)). It is the author's daily system: AOSP boots from the
`boot` slot, and the factory Android stays in `recovery` as a rescue system.

## What works

- **E Ink display**:
  - simpledrm + T1000 driver, with partial refreshes and a full GC on demand.
  - Per-app ink modes (text / mixed / video), a sleep image and a power-off image.
- **Touch and pen**: TT21000 (cyttsp5) and the HID pen.
- **Buttons, light and rotation**:
  - Power key with long press.
  - Cold/warm front light, plus a night light.
  - Rotation.
- **Wireless**: WiFi and Bluetooth (ConSys) and GPS. GPS uses the factory `mnld` under a small AIDL GNSS HAL.
- **Audio**: speaker and microphone (MT6351 codec, external amplifier).
- **Battery**:
  - Fuel gauge and charger.
  - "Always plugged" charge thresholds in Settings → Battery.
- **Suspend to RAM**: about 1.7 %/h unplugged (about 57 h of standby from a full charge).
- **Storage**: eMMC (HS400) and microSD (UHS SDR104).
- **Apps**: F-Droid with privileged extension, and Fennec. Their APKs are not included.
- **Own system apps**:
  - **EpadLauncher** (`apps/launcher`): an E Ink home screen in Kotlin without AndroidX. It has a clock, weather
    from Open-Meteo, quick tiles, notices, an A–Z app index with favourites and an "all apps" list, and
    E Ink-friendly navigation keys.
  - **Pantallas** (`apps/pantallas`): an editor for the sleep and power-off screens. It combines your own image,
    dithered to 16 grays, with text, time, date, battery, weather, QR codes and icons. The kernel keeps both images,
    so the power-off screen survives with the tablet off.

Known limits:
- SELinux is permissive.
- When the tablet is powered off with a USB cable connected, lk's charging mode repaints the screen.
- Some idle power is still spent in the MM power domain.

## Layout

| Path | What |
|------|------|
| `kernel/patches/` | Linux 7.2.11 patch series (`git am`): MT6797 support, T1000 driver, PMIC, ConSys, audio, DT |
| `kernel/configs/` | config fragments: `epad.config` + `android.config` (AOSP) |
| `kernel/dts/` | the E-PAD device tree |
| `aosp/device/eewrite/epad/` | device tree: board config, fstab, init, sepolicy, overlays, audio mixer, GNSS HAL |
| `aosp/device/eewrite/epad-apps/` | prebuilt app modules (F-Droid, Fennec; bring the APKs yourself) |
| `aosp/patches/<project>/` | patches to AOSP projects (minigbm simpledrm backend, SystemUI nav bar, Settings, audio HAL, soong) |
| `aosp/manifests/` | local manifest that drops unused projects so `soong_build` fits in ~18 GB of RAM |
| `apps/launcher`, `apps/pantallas` | the two system apps (Soong `android_app`, platform-signed) |
| `tools/` | the author's build and flash scripts |

## Building

The scripts are the ones actually used. They assume the working tree is at `/mnt/bilbi/epad`, so edit `E=` and the
paths to suit your own setup.

1. **Kernel.** Apply `kernel/patches` to Linux 7.2.11. Build with arm64 `defconfig` plus `epad.config` and
   `android.config`, and the DT from `kernel/dts`. The author uses `tools/build-mainline.sh` with `FRAG=android` and
   GCC 14.2. lk boots `Image.gz` with the DTB appended.
2. **AOSP.** Run `repo init -u https://android.googlesource.com/platform/manifest -b android-17.0.0_r1 --depth=1`.
   Optionally copy `aosp/manifests/local_manifest-recorte.xml` to `.repo/local_manifests/`. Then run `repo sync`.
3. **Firmware.** Run `tools/extraer-firmware.sh` with the tablet booted in its factory Android (adb + root, read
   only). It extracts the proprietary ConSys/SPM firmware, the unit's WiFi/BT NVRAM and the factory GPS daemon.
   **None of this is redistributed here.**
4. **Prepare and build.**
   - `tools/aosp-preparar.sh` copies the device tree, the apps, the kernel and the firmware into the AOSP tree and
     applies `aosp/patches`.
   - Then run `lunch epad-trunk_staging-userdebug && m` (see `tools/aosp-build.sh`).
   - A full build needs roughly 20 GB of RAM plus swap during Soong analysis.

## Installing

The factory partition table has no room for AOSP. The author's GPT shrinks the factory `userdata` to 8 GiB and adds
`epad_metadata` (16 MiB), `super` (6 GiB) and `epad_data` (the rest) at the end, keeping partitions 1–30 unchanged.
`tools/gpt-epad.py` builds that table from a dump of your own GPT. **Writing a GPT can brick the tablet: back up
everything and check the result before you write it.**

- `boot.img` goes to `boot`.
- The factory boot image (optionally patched with Magisk) goes to `recovery`, as a rescue system.
- `super.img` goes to `super`. `tools/aosp-actualizar.sh` does this from the factory Android. It writes `super` in
  verified sparse chunks (`tools/sparse-trozos.py`) and checks the md5 of every write.
- From AOSP, `adb reboot fabrica` (or `recovery`) sets the RTC flag that makes lk boot the `recovery` slot once.

## Safety

Never write the preloader. Never touch the `nvram`, `nvdata`, `protect*` or `proinfo` partitions; they hold
per-unit calibration and identity data. Proprietary firmware and per-unit data are not distributed here.
