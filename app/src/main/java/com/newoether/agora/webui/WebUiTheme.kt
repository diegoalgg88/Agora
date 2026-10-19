package com.newoether.agora.webui

/** La fuente de la app que el navegador debe cargar, reflejando la preferencia de Appearance. */
internal sealed interface WebUiFont {
    /** La fuente variable Mi Outfit empaquetada. */
    data object AppDefault : WebUiFont

    /** La fuente del device; el navegador usa su propia fuente del sistema. */
    data object System : WebUiFont

    /** Un archivo de fuente importado por el usuario dentro del almacenamiento de la app. */
    data class Custom(val path: String) : WebUiFont
}

/**
 * El color scheme Material resuelto de la app (preset o colores de wallpaper, claro u oscuro,
 * AMOLED) y su fuente, publicados por el tema Compose para que el navegador se vea como la app.
 *
 * [colors] mapea nombres de roles Material (`primary`, `onSurfaceVariant`, ...) a valores ARGB.
 */
internal data class WebUiTheme(
    val dark: Boolean,
    val colors: Map<String, Int>,
    val font: WebUiFont,
) {
    /** Custom properties de CSS que consume `style.css`: `--md-<role>` y `--app-font`. */
    fun toCss(): String = buildString {
        if (font != WebUiFont.System) {
            append("@font-face{font-family:\"AgoraApp\";src:url(\"$FONT_PATH?v=")
            append(fontVersion())
            append("\");font-weight:100 900;font-display:swap}\n")
        }
        append(":root{color-scheme:")
        append(if (dark) "dark" else "light")
        append(';')
        colors.toSortedMap().forEach { (role, argb) ->
            append("--md-").append(role.toKebabCase()).append(':').append(argb.toCssHex()).append(';')
        }
        append("--app-font:")
        append(if (font == WebUiFont.System) SYSTEM_STACK else "\"AgoraApp\",$SYSTEM_STACK")
        append("}\n")
    }

    /** Cambia cuando la fuente cambia, así el navegador no reusa una fuente cacheada. */
    private fun fontVersion(): String = when (font) {
        WebUiFont.AppDefault -> "app"
        WebUiFont.System -> "system"
        is WebUiFont.Custom -> Integer.toHexString(font.path.hashCode())
    }

    companion object {
        const val FONT_PATH = "/fonts/app"
        private const val SYSTEM_STACK = "system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif"

        private fun String.toKebabCase(): String =
            replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

        /** `#rrggbb`, o `#rrggbbaa` cuando no es opaco. */
        internal fun Int.toCssHex(): String {
            val rgb = String.format("#%06x", this and 0xFFFFFF)
            val alpha = (this ushr 24) and 0xFF
            return if (alpha == 0xFF) rgb else rgb + String.format("%02x", alpha)
        }
    }
}
