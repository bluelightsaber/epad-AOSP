# Táctil capacitivo (cyttsp5). Informa en coordenadas del panel apaisado (1872×1404, Y ya invertida en el DT);
# el panel está girado 270° respecto a la vista vertical de Android → ORIENTATION_270 (comprobado con
# RawToDisplay en aosp-20: x = 1404 − y_raw, y = x_raw).
touch.deviceType = touchScreen
touch.orientationAware = 1
touch.orientation = ORIENTATION_270
