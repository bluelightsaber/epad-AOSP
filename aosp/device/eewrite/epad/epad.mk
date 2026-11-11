# Eewrite E-PAD (Epad_PRO): MT6797, E Ink T1000 1872×1404, kernel mainline con la serie epad.
# Solo 64 bits: menos RAM y una ROM más pequeña; las apps de 32 bits no hacen falta en un lector.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
$(call inherit-product, device/eewrite/epad/device.mk)

PRODUCT_NAME := epad
PRODUCT_DEVICE := epad
PRODUCT_BRAND := Eewrite
PRODUCT_MANUFACTURER := Eewrite
PRODUCT_MODEL := E-PAD

# Español por omisión: el primer locale es el del sistema; tiene que ir en el producto, no en un heredado
PRODUCT_LOCALES := es_ES en_US
