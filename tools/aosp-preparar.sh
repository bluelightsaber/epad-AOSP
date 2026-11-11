#!/bin/sh
# Lleva lo nuestro al árbol AOSP (aosp/, fuera de git) antes de compilar:
#  - src/aosp/device/eewrite/epad      → aosp/device/eewrite/epad (copia: Soong no sigue enlaces de directorio)
#  - src/launcher                      → aosp/packages/apps/EpadLauncher
#  - src/pantallas                     → aosp/packages/apps/EpadPantallas
#  - src/aosp/device/eewrite/epad-apps + backup/apks/*.apk → aosp/device/eewrite/epad-apps (F-Droid, Fennec)
#  - kernel/out-linux-7.2-android      → aosp/device/eewrite/epad-kernel/Image.gz-dtb (Image.gz + DTB, para lk)
#  - src/aosp/patches/<proyecto>/*.patch → aplicados en aosp/<proyecto> (se saltan los ya aplicados)
# Compilar el kernel antes con: FRAG=android scripts/limitado.sh sh scripts/build-mainline.sh
set -e
E=/mnt/bilbi/epad
A=$E/aosp
K=$E/kernel/out-linux-7.2-android/arch/arm64/boot

mkdir -p $A/device/eewrite $A/device/eewrite/epad-kernel $A/packages/apps
rsync -a --delete --exclude="._*" $E/src/aosp/device/eewrite/epad/ $A/device/eewrite/epad/
rsync -a --delete --exclude="._*" $E/src/launcher/ $A/packages/apps/EpadLauncher/
rsync -a --delete --exclude="._*" $E/src/pantallas/ $A/packages/apps/EpadPantallas/
# apps de terceros: Android.bp en git, APK en backup/apks (fuera de git)
rsync -a --delete --exclude="._*" $E/src/aosp/device/eewrite/epad-apps/ $A/device/eewrite/epad-apps/
cp $E/backup/apks/*.apk $A/device/eewrite/epad-apps/
# firmware propietario (SPM, ConSys WMT/BT/Wi-Fi) y NVRAM de la unidad: lo saca scripts/extraer-firmware.sh del
# Android de fábrica a firmware-unidad/ (fuera de git) → device/eewrite/epad-firmware/connsys
[ -f $E/firmware-unidad/mediatek/WIFI_RAM_CODE_6797 ] ||
	{ echo "falta firmware-unidad/: ejecutar scripts/extraer-firmware.sh con la E-PAD en el Android de fábrica"; exit 1; }
mkdir -p $A/device/eewrite/epad-firmware
rm -rf $A/device/eewrite/epad-firmware/connsys; cp -r $E/firmware-unidad/mediatek $A/device/eewrite/epad-firmware/connsys
# GPS de fábrica (32 bits, Android 7) → /vendor/gps: su enlazador en /vendor/gps/linker (misma longitud que
# /system/bin/linker, se cambia en PT_INTERP) y libpropshim.so, que le da las propiedades de /vendor/gps/props
G=$A/device/eewrite/epad-firmware/gps
[ -f $E/firmware-unidad/gps/mnld ] ||
	{ echo "falta firmware-unidad/gps: ejecutar scripts/extraer-firmware.sh con la E-PAD en el Android de fábrica"; exit 1; }
rm -rf $G; cp -r $E/firmware-unidad/gps $G
sed -i "s|/system/bin/linker\x00|/vendor/gps/linker\x00|" $G/mnld
grep -q /vendor/gps/linker $G/mnld || { echo "no se pudo cambiar el intérprete de mnld"; exit 1; }
$A/prebuilts/clang/host/linux-x86/clang-r596125/bin/clang --target=armv7a-linux-androideabi -O2 -fPIC -shared \
	-nostdlib -fuse-ld=lld -DPROPS='"/vendor/gps/props"' -o $G/lib/libpropshim.so \
	$E/src/aosp/device/eewrite/epad/gnss/propshim.c $G/lib/libc.so -Wl,-soname,libpropshim.so

if [ -f $K/Image.gz ]; then
	cat $K/Image.gz $K/dts/mediatek/mt6797-epad.dtb > $A/device/eewrite/epad-kernel/Image.gz-dtb.nuevo
	cmp -s $A/device/eewrite/epad-kernel/Image.gz-dtb.nuevo $A/device/eewrite/epad-kernel/Image.gz-dtb &&
		rm $A/device/eewrite/epad-kernel/Image.gz-dtb.nuevo ||
		mv $A/device/eewrite/epad-kernel/Image.gz-dtb.nuevo $A/device/eewrite/epad-kernel/Image.gz-dtb
else
	echo "aviso: falta $K/Image.gz (kernel Android sin compilar)" >&2
fi

cd $E/src/aosp/patches
for p in $(find . -name "*.patch" | sort); do
	proy=$(dirname $p | sed "s|^\./||")
	if git -C $A/$proy apply --reverse --check $E/src/aosp/patches/$p 2>/dev/null; then
		echo "ya aplicado: $p"
	else
		git -C $A/$proy apply $E/src/aosp/patches/$p
		echo "aplicado: $p"
	fi
done
