package com.jotsarup.nowish.notes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import com.jotsarup.nowish.R

data class NoteColor(val key: String, val name: String, val light: Color, val dark: Color)

/** Keep-style pastel colors, with a deeper variant for dark mode. */
val NoteColors = listOf(
    NoteColor("default", "Default", Color.Unspecified, Color.Unspecified),
    NoteColor("coral", "Coral", Color(0xFFFAAFA8), Color(0xFF77172E)),
    NoteColor("peach", "Peach", Color(0xFFF39F76), Color(0xFF692B17)),
    NoteColor("sand", "Sand", Color(0xFFFFF8B8), Color(0xFF7C4A03)),
    NoteColor("mint", "Mint", Color(0xFFE2F6D3), Color(0xFF264D3B)),
    NoteColor("sage", "Sage", Color(0xFFB4DDD3), Color(0xFF0C625D)),
    NoteColor("fog", "Fog", Color(0xFFD4E4ED), Color(0xFF256377)),
    NoteColor("storm", "Storm", Color(0xFFAECCDC), Color(0xFF284255)),
    NoteColor("dusk", "Dusk", Color(0xFFD3BFDB), Color(0xFF472E5B)),
    NoteColor("blossom", "Blossom", Color(0xFFF6E2DD), Color(0xFF6C394F)),
    NoteColor("clay", "Clay", Color(0xFFE9E3D4), Color(0xFF4B443A)),
    NoteColor("chalk", "Chalk", Color(0xFFEFEFF1), Color(0xFF232427)),
)

data class NoteTheme(val key: String, val name: String, @DrawableRes val res: Int, val lightInk: Boolean)

/** The 20 pixel-art backgrounds (generated, 90×160, drawn with nearest-neighbour scaling). */
val NoteThemes = listOf(
    NoteTheme("sunset", "Sunset peaks", R.drawable.px_sunset, true),
    NoteTheme("night", "Starry night", R.drawable.px_night, true),
    NoteTheme("ocean", "Ocean", R.drawable.px_ocean, true),
    NoteTheme("forest", "Pine forest", R.drawable.px_forest, true),
    NoteTheme("desert", "Desert", R.drawable.px_desert, false),
    NoteTheme("city", "City lights", R.drawable.px_city, true),
    NoteTheme("sakura", "Sakura", R.drawable.px_sakura, false),
    NoteTheme("snow", "Snowy hills", R.drawable.px_snow, false),
    NoteTheme("volcano", "Volcano", R.drawable.px_volcano, true),
    NoteTheme("aurora", "Aurora", R.drawable.px_aurora, true),
    NoteTheme("planet", "Planet", R.drawable.px_planet, true),
    NoteTheme("rain", "Rainy day", R.drawable.px_rain, true),
    NoteTheme("synthwave", "Synthwave", R.drawable.px_synthwave, true),
    NoteTheme("meadow", "Meadow", R.drawable.px_meadow, false),
    NoteTheme("reef", "Reef", R.drawable.px_reef, true),
    NoteTheme("autumn", "Autumn", R.drawable.px_autumn, false),
    NoteTheme("galaxy", "Galaxy", R.drawable.px_galaxy, true),
    NoteTheme("beach", "Palm beach", R.drawable.px_beach, false),
    NoteTheme("candy", "Candy clouds", R.drawable.px_candy, false),
    NoteTheme("campfire", "Campfire", R.drawable.px_campfire, true),
)

fun noteTheme(key: String?): NoteTheme? = key?.let { k -> NoteThemes.find { it.key == k } }
fun noteColor(key: String?): NoteColor = NoteColors.find { it.key == key } ?: NoteColors.first()

@Composable
fun noteColorValue(key: String?, fallback: Color = MaterialTheme.colorScheme.surfaceContainerLow): Color {
    val c = noteColor(key)
    if (c.key == "default") return fallback
    return if (isSystemInDarkTheme()) c.dark else c.light
}

/** Text color that reads well on the note's color or pixel theme. */
@Composable
fun noteInk(color: String?, theme: String?): Color {
    val t = noteTheme(theme)
    if (t != null) return if (t.lightInk) Color.White else Color(0xFF1B1B1F)
    if (noteColor(color).key == "default") return MaterialTheme.colorScheme.onSurface
    return if (isSystemInDarkTheme()) Color(0xFFE8EAED) else Color(0xFF202124)
}

/** Soft shadow so text stays readable over the busier parts of a pixel theme. */
fun noteTextShadow(theme: String?): Shadow? {
    val t = noteTheme(theme) ?: return null
    return if (t.lightInk) Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)
    else Shadow(Color.White.copy(alpha = 0.7f), Offset(0f, 1f), 4f)
}

@Composable
fun PixelImage(bitmap: ImageBitmap, modifier: Modifier = Modifier, alignment: Alignment = Alignment.BottomCenter) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        filterQuality = FilterQuality.None,
    )
}

/** Paints a note's color or pixel-art theme behind [content], with a light scrim for legibility. */
@Composable
fun NoteBackground(
    color: String?,
    theme: String?,
    modifier: Modifier = Modifier,
    fallback: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = noteTheme(theme)
    Box(modifier.background(noteColorValue(color, fallback))) {
        if (t != null) {
            PixelImage(ImageBitmap.imageResource(t.res), Modifier.matchParentSize())
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (t.lightInk) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.50f))
            )
        }
        content()
    }
}
