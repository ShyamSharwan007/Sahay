package com.sahay.engine.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.sahay.core.contracts.PoiType
import com.sahay.engine.R

/**
 * Round map markers for the POI layer: a coloured disc with a white ring and a glyph from a vector drawable.
 * Shelters that are full or closed use the greyed variant.
 */
internal object MapIcons {
    private const val MARKER_DP = 34f
    private const val RING_DP = 2f
    private const val GLYPH_DP = 20f

    fun iconId(type: PoiType, off: Boolean): String =
        "poi_${type.name.lowercase()}" + if (off) "_off" else ""

    @DrawableRes
    private fun glyph(type: PoiType): Int = when (type) {
        PoiType.SHELTER -> R.drawable.ic_map_shelter
        PoiType.CANDIDATE_SHELTER -> R.drawable.ic_map_candidate_shelter
        PoiType.HOSPITAL -> R.drawable.ic_map_hospital
        PoiType.POLICE -> R.drawable.ic_map_police
    }

    private fun color(type: PoiType, palette: MapPalette): String = when (type) {
        PoiType.SHELTER -> palette.safe
        PoiType.CANDIDATE_SHELTER -> palette.primary
        PoiType.HOSPITAL -> palette.danger
        PoiType.POLICE -> palette.info
    }

    /** Every marker image for [palette], keyed by [iconId]. */
    fun build(context: Context, palette: MapPalette): Map<String, Bitmap> = buildMap {
        PoiType.entries.forEach { type ->
            put(iconId(type, off = false), marker(context, glyph(type), color(type, palette), palette))
            val canBeOff = type == PoiType.SHELTER || type == PoiType.CANDIDATE_SHELTER
            if (canBeOff) put(iconId(type, off = true), marker(context, glyph(type), palette.disabled, palette))
        }
    }

    private fun marker(context: Context, @DrawableRes glyphRes: Int, fill: String, palette: MapPalette): Bitmap {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val size = (MARKER_DP * density).toInt()
        // Created with the display metrics so MapLibre draws it MARKER_DP wide on screen.
        val bitmap = Bitmap.createBitmap(metrics, size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val ring = RING_DP * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor(palette.casing)
        canvas.drawCircle(centre, centre, centre, paint)
        paint.color = Color.parseColor(fill)
        canvas.drawCircle(centre, centre, centre - ring, paint)

        ContextCompat.getDrawable(context, glyphRes)?.mutate()?.let { drawable ->
            drawable.setTint(Color.parseColor(palette.onStatus))
            val half = (GLYPH_DP * density / 2f).toInt()
            drawable.setBounds(centre.toInt() - half, centre.toInt() - half, centre.toInt() + half, centre.toInt() + half)
            drawable.draw(canvas)
        }
        return bitmap
    }
}
