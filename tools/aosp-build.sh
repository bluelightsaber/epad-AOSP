#!/bin/sh
# Compila AOSP con los límites acordados (≤24 GB o MEM=, -j3 o J=). Uso: sh scripts/aosp-build.sh NN  → ../aosp-buildNN.log
set -e
cd "$(dirname "$0")/../aosp"
# NINJA_HIGHMEM_NUM_JOBS=1: un solo R8/metalava a la vez (build 32 murió por OOM con varios en paralelo)
export SOONG_INCREMENTAL_ANALYSIS=false GOMEMLIMIT=${GOMEM:-14GiB} GOGC=${GOGC_EPAD:-50} ALLOW_MISSING_DEPENDENCIES=true NINJA_HIGHMEM_NUM_JOBS=1
exec systemd-run --user --scope -q -p MemoryMax=${MEM:-24G} -p MemorySwapMax=14G -p AllowedCPUs=0-5 \
  bash -c "source build/envsetup.sh && lunch epad-trunk_staging-userdebug >/dev/null && m -j${J:-3}; echo FIN_BUILD=\$?" \
  > ../aosp-build$1.log 2>&1 < /dev/null
