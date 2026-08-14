package com.motorguard.ivi.ui.voice.nlu

import java.io.File
import java.text.Normalizer

/**
 * BERT uncased WordPiece tokenizer, in Kotlin.
 *
 * The embedding model expects exactly the token ids BERT would produce; feed it anything else
 * and it still returns a confident-looking 384-dim vector that means nothing. There is no
 * tokenizer in ONNX Runtime, and the usual answer — pulling in HuggingFace tokenizers as a
 * second native library — is a lot of .so for what is, for a vocabulary this size, a dictionary
 * lookup and a greedy longest-match loop.
 *
 * Faithful to the reference implementation in the ways that change the ids:
 *   - lower-case and strip accents (this is an *uncased* model)
 *   - split on whitespace and on punctuation, punctuation kept as its own token
 *   - greedy longest-match sub-words with the "##" continuation marker
 *   - anything unmatched becomes [UNK] rather than being dropped, so a strange word shifts
 *     the sentence vector instead of silently vanishing from it
 */
class WordPiece(private val vocab: Map<String, Int>) {

    fun encode(text: String, maxTokens: Int = MAX_TOKENS): IntArray {
        val ids = ArrayList<Int>(maxTokens)
        ids += vocab[CLS] ?: 0

        outer@ for (word in split(text)) {
            for (piece in pieces(word)) {
                // Leave room for the closing [SEP]; a truncated sentence still embeds usefully,
                // a missing [SEP] does not.
                if (ids.size >= maxTokens - 1) break@outer
                ids += piece
            }
        }

        ids += vocab[SEP] ?: 0
        return ids.toIntArray()
    }

    /** Lower-case, strip accents, then split on whitespace with punctuation as separate tokens. */
    private fun split(text: String): List<String> {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in folded) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                    out += ch.toString()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Greedy longest-match-first, the sub-word half of the algorithm. */
    private fun pieces(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(vocab[UNK] ?: 0)

        val out = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val candidate = if (start == 0) word.substring(start, end)
                else "##" + word.substring(start, end)
                val id = vocab[candidate]
                if (id != null) { found = id; break }
                end--
            }
            if (found == null) return listOf(vocab[UNK] ?: 0)  // whole word is unknown
            out += found
            start = end
        }
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        // The reference treats the ASCII symbol blocks as punctuation even though Unicode
        // classifies some of them (^, `, |, ~ …) as symbols.
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(ch).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }

    companion object {
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val UNK = "[UNK]"

        /** Utterances here are one spoken sentence; the model's own limit is 512. */
        const val MAX_TOKENS = 64

        private const val MAX_WORD_CHARS = 100

        /** vocab.txt is one token per line, the line number being the id. */
        fun load(vocabFile: File): WordPiece? = runCatching {
            val map = HashMap<String, Int>(32_768)
            vocabFile.useLines { lines ->
                lines.forEachIndexed { index, line -> map[line.trim()] = index }
            }
            if (map.isEmpty()) null else WordPiece(map)
        }.getOrNull()
    }
}
