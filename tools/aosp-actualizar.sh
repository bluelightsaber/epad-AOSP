#!/bin/sh
# Actualiza la producción (AOSP en boot p22 + super p32 de la eMMC) desde el Android de fábrica (ranura recovery).
# No toca epad_data ni epad_metadata. Uso: sh scripts/aosp-actualizar.sh [BOOT_IMG]
#   (por omisión aosp/out/target/product/epad/boot.img y super.img de la última compilación)
# Requisitos: AOSP arrancado con adb (se reinicia con «reboot fabrica») o ya en fábrica.
set -eu
E=/mnt/bilbi/epad; cd $E
A=$HOME/platform-tools/adb; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to your tablet}"; export ANDROID_SERIAL
P=aosp/out/target/product/epad; BOOT=${1:-$P/boot.img}; S=/tmp/act-super
esp() { for i in $(seq 60); do sleep 5; [ "$(timeout 5 $A get-state 2>/dev/null)" = device ] && break; done; sleep 15; }
ver() { timeout 10 $A shell getprop ro.build.version.release | tr -d "\r"; }
rm -rf $S; mkdir -p $S; python3 scripts/sparse-trozos.py $P/super.img $S >/dev/null; (cd $S; md5sum c*.bin > md5.txt)
echo "super: $(ls $S/c*.bin | wc -l) trozos, $(du -sh $S | cut -f1)"
[ "$(ver)" = 7.1.1 ] || { $A reboot fabrica; esp; }
[ "$(ver)" = 7.1.1 ] || { echo "no estoy en el Android de fábrica"; exit 1; }
SU() { timeout 600 $A shell "su -c \"$1\"" | tr -d "\r"; }
for n in "mmcblk0p22 boot" "mmcblk0p32 super" "mmcblk0p1 recovery"; do set -- $n
	SU "grep PARTNAME= /sys/class/block/$1/uevent" | grep -qx "PARTNAME=$2" || { echo "$1 no es $2"; exit 1; }; done
for f in $(cd $S; ls c*.bin); do b=${f#c}; b=${b%.bin}; n=$(( $(stat -c%s $S/$f) / 4096 ))
	timeout 300 $A push $S/$f /data/local/tmp/t.bin >/dev/null
	SU "dd if=/data/local/tmp/t.bin of=/dev/block/mmcblk0p32 bs=4096 seek=$b 2>/dev/null; sync; dd if=/dev/block/mmcblk0p32 bs=4096 skip=$b count=$n 2>/dev/null | md5sum; rm /data/local/tmp/t.bin" | cut -d" " -f1 > $S/r.txt
	grep -q "$(cat $S/r.txt)  $f" $S/md5.txt || { echo "trozo $f mal escrito"; exit 1; }
done
echo "super grabado y verificado"
SB=$(stat -c%s $BOOT); timeout 60 $A push $BOOT /data/local/tmp/boot.img >/dev/null
R=$(SU "dd if=/dev/zero of=/dev/block/mmcblk0p22 bs=1048576 count=16 2>/dev/null; dd if=/data/local/tmp/boot.img of=/dev/block/mmcblk0p22 bs=1048576 2>/dev/null; sync; echo 3 > /proc/sys/vm/drop_caches; dd if=/dev/block/mmcblk0p22 bs=4096 count=$((SB / 4096)) 2>/dev/null | md5sum; rm /data/local/tmp/boot.img" | cut -d" " -f1)
[ "$R" = "$(md5sum < $BOOT | cut -d" " -f1)" ] || { echo "boot mal escrito ($R)"; exit 1; }
echo "boot grabado y verificado ($R)"
$A reboot; esp
echo "arrancado: Android $(ver), $(timeout 10 $A shell uname -v | tr -d "\r")"
