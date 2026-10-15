package com.newoether.agora.ui.chat.message

/**
 * El parser GFM usado para el Markdown del chat no puede interrumpir un párrafo con una tabla,
 * así que una tabla cuyo header va directamente debajo de una línea de texto — la forma que los
 * modelos emiten con más frecuencia tras un heading en negritas — renderizaba `|` literales por
 * el resto del mensaje.
 *
 * [openTableBlocks] da a cada tabla su propio bloque antes del parseo. Una línea solo cuenta como
 * header de tabla cuando el delimiter row debajo la demuestra, de modo que la prosa que meramente
 * contiene un pipe queda intacta y el contenido de los fences permanece verbatim.
 */
internal fun String.openTableBlocks(): String {
    if (!contains('|') || !contains('\n')) return this
    val insertions = ArrayList<Int>(2)
    var lineStart = 0
    var previousLineStart = -1
    var previousLine = ""
    var fenceMarker: Char? = null
    var index = 0
    while (index <= length) {
        if (index != length && this[index] != '\n') {
            index++
            continue
        }
        val line = substring(lineStart, index).removeSuffix("\r")
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        val marker = if (indent < line.length) line[indent] else null
        val fenceRun = marker != null && (marker == '`' || marker == '~') &&
            line.startsWith("$marker$marker$marker", indent)
        when {
            fenceMarker == null && fenceRun -> fenceMarker = marker
            fenceMarker != null && fenceRun -> fenceMarker = null
            fenceMarker == null && previousLineStart > 0 &&
                previousLine.contains('|') && !isTableDelimiterRow(previousLine) &&
                isTableDelimiterRow(line) -> insertions += previousLineStart
        }
        previousLine = line
        previousLineStart = lineStart
        lineStart = index + 1
        index++
    }
    if (insertions.isEmpty()) return this
    val original = this
    val total = original.length
    return buildString(total + insertions.size) {
        var cursor = 0
        insertions.forEach { headerStart ->
            append(original, cursor, headerStart)
            append('\n')
            cursor = headerStart
        }
        append(original, cursor, total)
    }
}

/** `|---|:--:|` y similares: la fila que convierte a la línea de arriba en un header de tabla. */
private fun isTableDelimiterRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|') || !trimmed.contains('-')) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}
