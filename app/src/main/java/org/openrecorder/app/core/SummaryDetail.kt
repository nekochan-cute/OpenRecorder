package org.openrecorder.app.core

/** How much of a recording a summary keeps. [Standard] is the summary as it has always been. */
enum class SummaryDetail(val label: String, val instruction: String, val notes: String) {
    Brief("簡潔", "全体を短くまとめてください。概要は2〜3文、各見出しの項目は3つまでにし、細部は省いてください。",
        "要点を箇条書きで簡潔にまとめてください。"),
    Standard("標準", "", "要点を箇条書きで簡潔にまとめてください。"),
    Detailed("詳細", "省略せずに詳しく書いてください。話題ごとに見出しを分け、話の流れと理由、数値・日付・固有名詞・発言者を残し、項目数は制限しないでください。",
        "話された内容を漏れなく箇条書きにまとめてください。数値・日付・固有名詞・理由も残してください。");

    /** A list length scaled from the [Standard] one. */
    fun count(standard: Int) = when (this) {
        Brief -> (standard + 2) / 3
        Standard -> standard
        Detailed -> standard * 2
    }
}
