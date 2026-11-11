#!/bin/sh
# Extrae del Android de fábrica de la propia E-PAD (adb + su, solo lectura) el firmware propietario que necesita
# el kernel mainline y lo deja con la estructura de /lib/firmware/mediatek que esperan los drivers:
#   ROMv3_patch_1_*_hdr.bin, WIFI_RAM_CODE_6797   ConSys (WMT/BT/Wi-Fi), de /system/vendor/firmware
#   mt6797/pcm_suspend.bin, pcm_vcorefs_lpm.bin    SPM (suspensión), de /system/vendor/firmware
#   mt6797/BT.bin                                  primeros 64 B del NVRAM BT_Addr (dirección BT de la unidad)
#   mt6797-epad-wifi-nvram.bin                     primeros 512 B del NVRAM WIFI (MAC y calibración RF de la unidad)
# y a ../gps (firmware-unidad/gps) el GPS de fábrica, que en AOSP corre tal cual (ver src/aosp/device/eewrite/epad/gnss):
#   mnld, linker, lib/*.so                         demonio de 32 bits de Android 7 con su enlazador y bibliotecas
#   lib64/gps.mt6797.so                            HAL heredado de 64 bits (lo carga android.hardware.gnss-service.epad)
#   nvram/GPS, nvram/fstab.mt6797                  NVRAM del GPS (TCXO, LNA) y el fstab que lee libnvram
# No se redistribuye nada: cada cual lo saca de su tablet. Uso: scripts/extraer-firmware.sh [DESTINO]
# (por defecto firmware-unidad/mediatek). Con varias tablets/teléfonos conectados, fijar ANDROID_SERIAL.
set -eu
ADB=${ADB:-adb}
D=${1:-firmware-unidad/mediatek}
mkdir -p "$D/mt6797"
saca() { # saca ORIGEN DESTINO [BYTES]
	if [ -n "${3:-}" ]; then
		$ADB exec-out "su -c 'dd if=$1 bs=$3 count=1 2>/dev/null'" > "$2"
	else
		$ADB exec-out "su -c 'cat $1'" > "$2"
	fi
	[ -s "$2" ] || { echo "vacío: $1 (¿Android de fábrica con root?)" >&2; exit 1; }
	echo "$2 ($(wc -c < "$2") B)"
}
[ "$($ADB shell getprop ro.product.name | tr -d '\r')" = full_ble6797_6m_n ] ||
	{ echo "no es el Android de fábrica de la E-PAD (ro.product.name)" >&2; exit 1; }
F=/system/vendor/firmware
for f in ROMv3_patch_1_0_hdr.bin ROMv3_patch_1_1_hdr.bin WIFI_RAM_CODE_6797; do saca $F/$f "$D/$f"; done
for f in pcm_suspend.bin pcm_vcorefs_lpm.bin; do saca $F/$f "$D/mt6797/$f"; done
N=/data/nvram/APCFG/APRDEB
saca $N/BT_Addr "$D/mt6797/BT.bin" 64
saca $N/WIFI "$D/mt6797-epad-wifi-nvram.bin" 512
G=$(dirname "$D")/gps
mkdir -p "$G/lib" "$G/lib64" "$G/nvram"
saca /system/vendor/bin/mnld "$G/mnld"
saca /system/bin/linker "$G/linker"
for f in libc++.so libc.so libcrypto.so libcurl.so libcustom_nvram.so libcutils.so libdl.so libfs_mgr.so liblog.so \
	libm.so libmnl.so libnvram.so libnvram_platform.so libnvram_sec.so libssl.so libstdc++.so libz.so; do
	l=/system/vendor/lib/$f; $ADB shell "su -c 'test -f $l'" || l=/system/lib/$f
	saca $l "$G/lib/$f"
done
saca /system/vendor/lib64/hw/gps.mt6797.so "$G/lib64/gps.mt6797.so"
saca $N/GPS "$G/nvram/GPS"
saca /data/nvram/fstab.mt6797 "$G/nvram/fstab.mt6797"
