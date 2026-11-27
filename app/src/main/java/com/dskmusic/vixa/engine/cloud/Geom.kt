package com.dskmusic.vixa.engine.cloud

import android.graphics.Bitmap
import android.graphics.Path

/** Forma lista para dibujar: trazado en la caja unidad o mascara de mapa de bits. */
class Geom(val path: Path?, val bitmap: Bitmap?, val strokeNorm: Float = 0f)
