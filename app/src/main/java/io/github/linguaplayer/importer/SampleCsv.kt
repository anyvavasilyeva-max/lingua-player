package io.github.linguaplayer.importer

/**
 * Пример файла для импорта, встроенный в приложение.
 * Три строки показывают, как заполнять слово, фразу и теги.
 */
object SampleCsv {
    const val FILE_NAME = "lingua_player_пример.csv"

    val HEADER = listOf("source", "translation", "type", "tags")

    val ROWS = listOf(
        // Слово: тип word, один тег.
        listOf("포크", "Вилка", "word", "кухня"),
        // Фраза: тип phrase, несколько тегов через «;».
        listOf("설거지를 하다", "Мыть посуду", "phrase", "кухня;быт"),
        // Несколько переводов в одной ячейке и несколько тегов.
        listOf("배", "Груша; живот; лодка", "word", "TOPIK 2급;Сложные"),
    )

    fun rows(): List<List<String>> = listOf(HEADER) + ROWS

    fun bytes(): ByteArray = CsvWriter.toBytes(rows())
}
