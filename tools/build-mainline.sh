#!/bin/sh
# Compila Linux mainline para la E-PAD: defconfig arm64 + src/mainline/epad.config y el DT
# src/mainline/mt6797-epad.dts. Compilador: GCC 14.2 de kernel.org (tools/gcc14).
# Árbol: kernel/$SRC (por defecto linux-7.2); salida en kernel/out-$SRC.
# FRAG=android: src/mainline/android.config en lugar de pmos.config (AOSP), salida en kernel/out-$SRC-android.
# EXTRA="drm": fragmentos adicionales src/mainline/<nombre>.config (p. ej. drm = mediatek-drm, DTB -drm).
set -e
E=/mnt/bilbi/epad
K=$E/kernel
SRC=${SRC:-linux-7.2}
S=$K/$SRC
FRAG=${FRAG:-pmos}
O=$K/out-$SRC
[ "$FRAG" = pmos ] || O=$O-$FRAG
[ -z "${EXTRA:-}" ] || O=$O-$(echo $EXTRA | tr " " -)
export ARCH=arm64 CROSS_COMPILE=$E/tools/gcc14/gcc-14.2.0-nolibc/aarch64-linux/bin/aarch64-linux-

D=$S/arch/arm64/boot/dts/mediatek
cp $E/src/mainline/mt6797-epad*.dts $D/
# Drivers propios: src/mainline es la fuente; se copian solo si cambian (no fuerza recompilar)
for f in $E/src/mainline/*.c; do
	t=$(find $S/drivers -name $(basename $f) | head -1)
	[ -n "$t" ] && ! cmp -s $f $t && cp $f $t
done
for f in $E/src/mainline/*.h; do
	t=$S/include/linux/$(basename $f)
	cmp -s $f $t || cp $f $t
done
for d in $E/src/mainline/mt6797-epad*.dts; do
	b=$(basename $d .dts)
	grep -q "$b.dtb" $D/Makefile || echo "dtb-\$(CONFIG_ARCH_MEDIATEK) += $b.dtb" >> $D/Makefile
done
X=""
for x in ${EXTRA:-}; do X="$X $E/src/mainline/$x.config"; done

cd $S
make O=$O defconfig
$S/scripts/kconfig/merge_config.sh -m -O $O $O/.config $E/src/mainline/solo-mediatek.config $E/src/mainline/reducir.config $E/src/mainline/epad.config $E/src/mainline/$FRAG.config$X
make O=$O olddefconfig
make O=$O -j4 Image.gz mediatek/mt6797-epad.dtb mediatek/mt6797-epad-drm.dtb "$@"
ls -l $O/arch/arm64/boot/Image $O/arch/arm64/boot/Image.gz $O/arch/arm64/boot/dts/mediatek/mt6797-epad.dtb
