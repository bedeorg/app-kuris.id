package com.example.util

object PlateOcrUtils {

    // Daftar kode plat nomor kendaraan bermotor seluruh wilayah Indonesia
    val INDONESIAN_PLATE_CODES = setOf(
        // Jawa & Jakarta
        "A", "B", "D", "E", "F", "G", "H", "K", "L", "M", "N", "P", "R", "S", "T", "W", "Z",
        "AA", "AB", "AD", "AE", "AG",
        // Sumatera
        "BA", "BB", "BD", "BE", "BG", "BH", "BK", "BL", "BM", "BN", "BP",
        // Kalimantan
        "DA", "KB", "KH", "KT", "KU",
        // Sulawesi
        "DB", "DC", "DD", "DE", "DG", "DH", "DL", "DM", "DN", "DP", "DT",
        // Bali & Nusa Tenggara
        "DK", "DR", "EA", "EB", "ED",
        // Maluku & Papua
        "PA", "PB"
    )

    private val NOISE_CODES = setOf("ID", "NO", "KG", "RP", "JL", "CD", "CC", "PO", "PT", "CV", "CS", "SJ")
    private val NOISE_SUFFIXES = setOf("WIB", "WITA", "WIT", "KGS", "PCS", "BOX", "MTR", "LTR", "JAN", "FEB", "MAR", "APR", "MEI", "JUN", "JUL", "AGU", "SEP", "OKT", "NOV", "DES")

    /**
     * Mengekstrak nomor polisi kendaraan Indonesia (contoh: "B 9033 BEK", "b 9033 bek") dari teks hasil OCR.
     */
    fun extractIndonesianNopol(rawText: String): String? {
        if (rawText.isBlank()) return null

        // 1. Cek dengan awalan kata kunci dokumen (Nopol: B 9033 BEK, Plat: B 9033 BEK, No Pol: B 9033 BEK, dll.)
        val labeledRegex = Regex(
            """(?:nopol|plat|no\.?\s*pol(?:isi)?|kendaraan|armada|mobil|truk)\s*[:=-]?\s*([A-Za-z]{1,2})\s*[-.]?\s*(\d{1,4})\s*[-.]?\s*([A-Za-z]{1,3})""",
            RegexOption.IGNORE_CASE
        )
        labeledRegex.find(rawText)?.let { match ->
            val code = match.groupValues[1].uppercase()
            val num = match.groupValues[2]
            val suffix = match.groupValues[3].uppercase()
            if ((code in INDONESIAN_PLATE_CODES || code !in NOISE_CODES) && suffix !in NOISE_SUFFIXES) {
                return "$code $num $suffix"
            }
        }

        // 2. Cek pola standar plat nomor Indonesia pada teks
        val plateRegex = Regex(
            """\b([A-Za-z]{1,2})\s*[-.]?\s*(\d{1,4})\s*[-.]?\s*([A-Za-z]{1,3})\b""",
            RegexOption.IGNORE_CASE
        )

        val matches = plateRegex.findAll(rawText).toList()
        for (match in matches) {
            val code = match.groupValues[1].uppercase()
            val num = match.groupValues[2]
            val suffix = match.groupValues[3].uppercase()

            if (code in NOISE_CODES || suffix in NOISE_SUFFIXES) {
                continue
            }

            if (code in INDONESIAN_PLATE_CODES) {
                return "$code $num $suffix"
            }
        }

        // 3. Multi-line heuristic (jika plat nomor dan huruf belakang terpisah baris pada OCR foto plat fisik)
        // Baris 1: B 9033
        // Baris 2: BEK
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in 0 until lines.size - 1) {
            val line1 = lines[i]
            val line2 = lines[i + 1]

            val codeAndNumMatch = Regex("""\b([A-Za-z]{1,2})\s*[-.]?\s*(\d{1,4})\b""").find(line1)
            val suffixMatch = Regex("""\b([A-Za-z]{1,3})\b""").find(line2)

            if (codeAndNumMatch != null && suffixMatch != null) {
                val code = codeAndNumMatch.groupValues[1].uppercase()
                val num = codeAndNumMatch.groupValues[2]
                val suffix = suffixMatch.groupValues[1].uppercase()
                if (code in INDONESIAN_PLATE_CODES && suffix !in NOISE_SUFFIXES) {
                    return "$code $num $suffix"
                }
            }
        }

        // 4. Fallback jika ada kode 1-2 huruf yang belum terdaftar di list kode
        for (match in matches) {
            val code = match.groupValues[1].uppercase()
            val num = match.groupValues[2]
            val suffix = match.groupValues[3].uppercase()
            if (code !in NOISE_CODES && suffix !in NOISE_SUFFIXES && num.length in 1..4) {
                return "$code $num $suffix"
            }
        }

        return null
    }
}
