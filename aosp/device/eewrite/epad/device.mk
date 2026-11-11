# Eewrite E-PAD — contenido del producto (ESQUELETO: ver «Pendiente» en notes/aosp.md)

PRODUCT_SHIPPING_API_LEVEL := 37
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_CHARACTERISTICS := tablet

# Pantalla E-Ink 10,3" 1872×1404 (~227 ppp)
PRODUCT_AAPT_CONFIG := normal large xlarge
PRODUCT_AAPT_PREF_CONFIG := hdpi
# 280 en vez de 240 (≈ 227 ppp reales): letra y botones algo mayores por defecto, a petición del usuario (aosp-22)
PRODUCT_PRODUCT_PROPERTIES += ro.sf.lcd_density=280

# Hora de Madrid por omisión (el idioma va en epad.mk: aquí lo pisaba full_base.mk)
PRODUCT_PRODUCT_PROPERTIES += persist.sys.timezone=Europe/Madrid
# Sin acelerómetro: la rotación es siempre la del usuario (botón Girar). Sin esto pasaba sola a 90° al
# terminar el arranque (aosp-19).
PRODUCT_COPY_FILES += device/eewrite/epad/display_settings.xml:$(TARGET_COPY_OUT_VENDOR)/etc/display_settings.xml

# Plantilla del suplicante WiFi (la copia a /data/vendor/wifi/wpa la primera vez)
PRODUCT_COPY_FILES += device/eewrite/epad/wifi/wpa_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant.conf
# Táctil: orientación respecto al panel (ver idc/cyttsp5.idc)
PRODUCT_COPY_FILES += device/eewrite/epad/idc/cyttsp5.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/cyttsp5.idc

# SPM y ConSys (WMT/BT/Wi-Fi) son internos y piden el firmware antes de montar /vendor: van en /lib/firmware del
# ramdisk (aosp-21: sin él, hci0 no se configuraba y la HAL de BT giraba al 100 % en waitHciDev()). Mismo árbol
# que el initramfs de pmOS; lo extrae scripts/extraer-firmware.sh del Android de fábrica (aosp-preparar.sh lo copia).
EPAD_FW := device/eewrite/epad-firmware/connsys
# También en /vendor/firmware (firmware_class.path): lo que se pide tras montar /vendor, p. ej. BT.bin al
# encender el Bluetooth (el ramdisk ya no está).
EPAD_FW_FILES := $(shell cd $(EPAD_FW) 2>/dev/null && find . -type f | sed "s|^\./||")
PRODUCT_COPY_FILES += \
    $(foreach f,$(EPAD_FW_FILES),$(EPAD_FW)/$(f):$(TARGET_COPY_OUT_RAMDISK)/lib/firmware/mediatek/$(f)) \
    $(foreach f,$(EPAD_FW_FILES),$(EPAD_FW)/$(f):$(TARGET_COPY_OUT_VENDOR)/firmware/mediatek/$(f))

# El panel es apaisado (1872×1404) pero la tablet se usa en vertical: lo declara el DT (rotation = <270> en
# el simple-framebuffer → «panel orientation» del conector) y drm_hwcomposer se lo pasa a SurfaceFlinger.

# Sin animaciones (cada fotograma intermedio es un refresco de tinta): escalas a 0 en el overlay de
# SettingsProvider; sin animación de arranque (se queda el logo de lk). Sin precalentar shaders: por software
# el RenderEngine tardaba ~40 s y SystemUI daba ANR esperando en getGpuContextPriority() (aosp-21).
PRODUCT_PACKAGE_OVERLAYS += device/eewrite/epad/overlay
PRODUCT_PRODUCT_PROPERTIES += \
    debug.sf.nobootanimation=1 \
    service.sf.prime_shader_cache=0 \
    persist.sys.zram_enabled=1

# Cabecera de boot v0 (lk): sin vendor_boot, el fstab de la primera etapa va en el ramdisk de boot
PRODUCT_COPY_FILES += \
    device/eewrite/epad/fstab.mt6797:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.mt6797 \
    device/eewrite/epad/fstab.mt6797:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.mt6797 \
    device/eewrite/epad/init.mt6797.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.mt6797.rc \
    device/eewrite/epad/init.mt6797.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.mt6797.usb.rc \
    device/eewrite/epad/ueventd.mt6797.rc:$(TARGET_COPY_OUT_VENDOR)/etc/ueventd.rc

# Gráficos, como la Dragonboard en su variante por software (linaro_swr): drm_hwcomposer sobre simpledrm (el
# framebuffer de lk; el kernel pide el refresco al T1000), minigbm genérico (búferes «dumb» de simpledrm, parche
# en src/aosp/patches/external/minigbm) y GLES/Vulkan por software con ANGLE + SwiftShader. La GPU (Mali-T880,
# panfrost) queda para más adelante; en E-Ink no hace falta.
$(call inherit-product, device/linaro/dragonboard/shared/graphics/drm_hwcomposer/device.mk)
$(call inherit-product, device/linaro/dragonboard/shared/graphics/swangle/device.mk)
$(call soong_config_set,minigbm,platform,generic)
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator-service.minigbm \
    mapper.minigbm
PRODUCT_VENDOR_PROPERTIES += \
    ro.hardware.gralloc=minigbm \
    vendor.hwc.drm.device=/dev/dri/card0

# El HAL de efectos de ejemplo no arranca sin /vendor/etc/audio_effects_config.xml (y sin él audioserver tampoco,
# y system_server se bloquea en AudioService y lo mata su watchdog)
$(call soong_config_set_bool,hardware_interfaces_audio,use_default_audio_effects_config,true)
# Configuración de audio genérica + Bluetooth (el HAL AIDL de ejemplo crea sus módulos, primary → IModule/default y r_submix,
# a partir de audio_policy_configuration.xml; sin él no registra ninguno y audioserver espera para siempre)
AP_CFG := frameworks/av/services/audiopolicy/config
PRODUCT_COPY_FILES += \
    device/eewrite/epad/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    $(AP_CFG)/bluetooth_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_audio_policy_configuration_7_0.xml \
    device/eewrite/epad/audio/primary_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/primary_audio_policy_configuration.xml \
    $(AP_CFG)/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
    $(AP_CFG)/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(AP_CFG)/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \
    $(AP_CFG)/surround_sound_configuration_5_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/surround_sound_configuration_5_0.xml
# HAL genéricos (batería por power_supply, audio de relleno hasta tener códec: el HAL de ejemplo solo se instala
# dentro de su APEX; energía de ejemplo: sin IPower, HintManagerService tumba system_server)
PRODUCT_PACKAGES += \
    android.hardware.health-service.example \
    android.hardware.bluetooth-service.default \
    com.android.hardware.audio \
    audio_effects_config.xml \
    android.hardware.power-service.example \
    wpa_supplicant \
    hostapd

# GPS (ConSys, /dev/stpgps del kernel): mnld y HAL heredado de fábrica tal cual (32 bits de Android 7, con su
# enlazador; los extrae scripts/extraer-firmware.sh) bajo un HAL AIDL propio (gnss/). Modos en config.fs.
EPAD_GPS := device/eewrite/epad-firmware/gps
EPAD_GPS_LIBS := $(notdir $(wildcard $(EPAD_GPS)/lib/*.so))
PRODUCT_COPY_FILES += \
    $(EPAD_GPS)/mnld:$(TARGET_COPY_OUT_VENDOR)/gps/mnld \
    $(EPAD_GPS)/linker:$(TARGET_COPY_OUT_VENDOR)/gps/linker \
    $(foreach f,$(EPAD_GPS_LIBS),$(EPAD_GPS)/lib/$(f):$(TARGET_COPY_OUT_VENDOR)/gps/lib/$(f)) \
    $(EPAD_GPS)/nvram/GPS:$(TARGET_COPY_OUT_VENDOR)/gps/nvram/GPS \
    $(EPAD_GPS)/nvram/fstab.mt6797:$(TARGET_COPY_OUT_VENDOR)/gps/nvram/fstab.mt6797 \
    $(EPAD_GPS)/lib64/gps.mt6797.so:$(TARGET_COPY_OUT_VENDOR)/lib64/hw/gps.mt6797.so \
    device/eewrite/epad/gnss/props:$(TARGET_COPY_OUT_VENDOR)/gps/props \
    frameworks/native/data/etc/android.hardware.location.gps.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.location.gps.xml
PRODUCT_PACKAGES += android.hardware.gnss-service.epad
PRODUCT_PACKAGES += epad-audio-mixer

# KeyMint por software (no hay TEE): keystore2 y vold lo necesitan para el cifrado de /data y /metadata
PRODUCT_PACKAGES += android.hardware.security.keymint-service.nonsecure

# Características que tiene la tablet (WiFi, BT, táctil multipunto, lápiz)
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/tablet_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/tablet_core_hardware.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml

# adb por USB desde el principio (userdebug)
PRODUCT_SYSTEM_PROPERTIES += persist.sys.usb.config=adb

# Launcher propio (src/launcher)
PRODUCT_PACKAGES += EpadLauncher EpadPantallas
# sin Google: tienda F-Droid (con extensión privilegiada) y navegador Fennec
PRODUCT_PACKAGES += FDroid FDroidPrivilegedExtension Fennec

# Heap de Dalvik de tableta (sin esto el límite es 16 MB y system_server muere por OutOfMemoryError)
$(call inherit-product, frameworks/native/build/tablet-10in-xhdpi-2048-dalvik-heap.mk)

# Kernel precompilado (scripts/build-mainline.sh FRAG=android + aosp-preparar.sh) -> $(PRODUCT_OUT)/kernel
PRODUCT_COPY_FILES += device/eewrite/epad-kernel/Image.gz-dtb:kernel

# VINTF: manifest mínimo; el kernel mainline 7.2 no está en las matrices de compatibilidad
DEVICE_MANIFEST_FILE := device/eewrite/epad/manifest.xml
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

# Barra de navegación de SystemUI en vez de la taskbar de Launcher3 (no la tenemos); parche en
# src/aosp/patches/frameworks/base/0001
PRODUCT_PRODUCT_PROPERTIES += ro.epad.force_navbar=true
