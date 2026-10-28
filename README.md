# Modern Android for the Eewrite E-PAD (Epad_PRO)

A port of a current Android (AOSP-based) to the **Eewrite E-PAD**, a 10.3" E Ink tablet (1872×1404) built around
the MediaTek **MT6797** (Helio X20/X25), MT6351 PMIC, T1000 E Ink controller, TT21000 touch panel and a HID pen.

The tablet ships with **Android 7.1.1** on a vendor 3.18 kernel, without Project Treble, so a GSI is not an
option: a modern Android needs a modern kernel and a device tree written for this hardware.

## Status

**Not started yet.** This repository is the home for that work; it will reuse the hardware enablement done for
the mainline Linux / postmarketOS port, which already runs on the device:

- [epad-pmos](https://github.com/bluelightsaber/epad-pmos): Linux 7.2 patch series (all 10 CPUs, cpufreq,
  thermal, E Ink T1000 driver, touch, pen, power key, RTC/power-off, battery, charger, WiFi/BT CONNSYS, eMMC,
  USB), postmarketOS packages and a KOReader port.

## Plan (tentative)

1. Base: Android generic kernel / mainline-based kernel carrying the epad-pmos series.
2. Device tree and vendor bits: display through the T1000 driver (E Ink refresh modes exposed to apps),
   touch + pen input, front light (cold/warm PWM LEDs), battery/charger HAL, WiFi/BT firmware loading.
3. AOSP device configuration and a minimal, E Ink-friendly system image.

Open problems shared with the Linux port: suspend to RAM (MT6797 SPM), audio, sensors.

## Safety

Never write the preloader, and never touch the `nvram`, `nvdata`, `protect*` or `proinfo` partitions. Proprietary
firmware and per-unit calibration data are not distributed here.
