# Eewrite E-PAD — MT6797 (2×A72 + 8×A53; el big no se usa en Android al principio, ver notes/aosp.md)

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_VARIANT := cortex-a53
TARGET_CPU_ABI := arm64-v8a

TARGET_BOARD_PLATFORM := mt6797
TARGET_BOOTLOADER_BOARD_NAME := epad
TARGET_NO_BOOTLOADER := true

# El Android de fábrica (7.1.1) se queda en la ranura recovery para seguir arrancándolo.
TARGET_NO_RECOVERY := true

# --- Kernel: Linux mainline + serie epad, compilado fuera (scripts/build-mainline.sh) con android.config.
# lk de fábrica: cabecera v0, DTB pegado al Image.gz, mismas direcciones que scripts/slot-test.sh.
TARGET_NO_KERNEL := false
TARGET_PREBUILT_KERNEL := device/eewrite/epad-kernel/Image.gz-dtb
BOARD_BOOT_HEADER_VERSION := 0
BOARD_KERNEL_BASE := 0x40078000
BOARD_KERNEL_PAGESIZE := 2048
BOARD_MKBOOTIMG_ARGS := --header_version 0 --kernel_offset 0x00188000 --ramdisk_offset 0x04f88000 \
    --second_offset 0x00e88000 --tags_offset 0x03f88000
# lk antepone su propia línea (androidboot.hardware=mt6797, serie, etc.); esto va detrás.
BOARD_KERNEL_CMDLINE := bootopt=64S3,32N2,64N2 maxcpus=10 panic=5 slub_debug=-
# lk solo conserva ~99 caracteres de esta línea: boot_devices va en el DT (firmware/android), la ruta del firmware
# en init.mt6797.rc y androidboot.selinux=permissive lo añade lk (bootloader desbloqueado).
# audit=0: en permisivo las denegaciones de SELinux inundan el kmsg (y pstore)
BOARD_KERNEL_CMDLINE += printk.devkmsg=on audit=0

# --- Particiones: boot en la eMMC (16 MB); el resto en la microSD (GPT: metadata, super, userdata),
# para no tocar system/userdata del Android de fábrica.
BOARD_FLASH_BLOCK_SIZE := 4096
BOARD_BOOTIMAGE_PARTITION_SIZE := 16777216
BOARD_USES_METADATA_PARTITION := true
TARGET_USE_DYNAMIC_PARTITIONS := true
BOARD_BUILD_SUPER_IMAGE_BY_DEFAULT := true
BOARD_SUPER_PARTITION_SIZE := 6442450944
BOARD_SUPER_PARTITION_GROUPS := epad_dynamic_partitions
BOARD_EPAD_DYNAMIC_PARTITIONS_SIZE := 6438256640
BOARD_EPAD_DYNAMIC_PARTITIONS_PARTITION_LIST := system system_ext product vendor
BOARD_SUPER_PARTITION_METADATA_DEVICE := super

BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_PRODUCT := product
TARGET_USERIMAGES_USE_F2FS := true
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs
BOARD_F2FS_BLOCKSIZE := 4096
BOARD_USERDATAIMAGE_PARTITION_SIZE := 4294967296

PRODUCT_FULL_TREBLE := true

# --- WiFi (driver CONNSYS de la serie epad, cfg80211 estándar) y Bluetooth (HCI estándar)
WPA_SUPPLICANT_VERSION := VER_0_8_X
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_HOSTAPD_DRIVER := NL80211
BOARD_HAVE_BLUETOOTH := true

BOARD_VENDOR_SEPOLICY_DIRS += device/eewrite/epad/sepolicy

# Gráficos: drm_hwcomposer + minigbm + ANGLE/SwiftShader (ver device.mk)
include device/linaro/dragonboard/shared/graphics/drm_hwcomposer/BoardConfig.mk
BOARD_SEPOLICY_DIRS += device/linaro/dragonboard/shared/graphics/swangle/sepolicy

BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true
# ejecutables copiados tal cual (GPS de fábrica): PRODUCT_COPY_FILES los deja en 0644
TARGET_FS_CONFIG_GEN := device/eewrite/epad/config.fs

# Una sola ranura: lk no conoce slots; boot en la eMMC, super en la microSD (AOSP 17 activa A/B por defecto)
AB_OTA_UPDATER := false
