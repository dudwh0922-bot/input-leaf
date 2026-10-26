package com.inputleaf.android.inject

import android.view.KeyEvent

/**
 * Korean "dubeolsik" (2-set) syllable composer.
 *
 * Feed it compatibility jamo (ㄱ, ㅏ, ...) one at a time with [input]. It returns the text that is
 * now final ([Result.committed]) and the syllable that is still being built ([Result.composing]).
 * The composing syllable is meant to be shown with InputConnection.setComposingText.
 *
 * The current syllable is kept as the list of jamo typed for it, so [backspace] simply removes the
 * last jamo (한 -> 하 -> ㅎ -> empty), like the stock Korean keyboards do.
 */
class HangulComposer {

    data class Result(val committed: String, val composing: String)

    private data class Syllable(
        val cho: Char?,
        val jung: Char?,
        val jong: Char?,
        val jongLen: Int,
    )

    private val buf = ArrayList<Char>(5)

    val isComposing: Boolean get() = buf.isNotEmpty()

    fun input(jamo: Char): Result {
        // 1) The jamo still fits into the syllable being built.
        buf.add(jamo)
        if (parse(buf) != null) {
            return Result("", render(buf))
        }
        buf.removeAt(buf.lastIndex)

        // 2) A vowel after a final consonant: the last consonant moves on as the next syllable's
        //    initial (갑 + ㅏ -> 가 + ㅂㅏ, 닭 + ㅣ -> 달 + ㄱㅣ).
        val current = parse(buf)
        if (isVowel(jamo) && current != null && current.jongLen > 0) {
            val moved = buf.removeAt(buf.lastIndex)
            val committed = render(buf)
            buf.clear()
            buf.add(moved)
            buf.add(jamo)
            return Result(committed, render(buf))
        }

        // 3) Anything else ends the syllable and starts a new one.
        val committed = render(buf)
        buf.clear()
        buf.add(jamo)
        return Result(committed, render(buf))
    }

    /** Removes the last typed jamo. Returns null when nothing is being composed. */
    fun backspace(): Result? {
        if (buf.isEmpty()) return null
        buf.removeAt(buf.lastIndex)
        return Result("", if (buf.isEmpty()) "" else render(buf))
    }

    /** Ends the composition and returns the text that was being composed. */
    fun flush(): String {
        val text = if (buf.isEmpty()) "" else render(buf)
        buf.clear()
        return text
    }

    fun reset() {
        buf.clear()
    }

    private fun parse(jamo: List<Char>): Syllable? {
        if (jamo.isEmpty()) return null
        val first = jamo[0]

        if (isVowel(first)) {
            // Vowel without an initial consonant: a lone vowel or a compound vowel (ㅗ+ㅏ = ㅘ).
            return when {
                jamo.size == 1 -> Syllable(null, first, null, 0)
                jamo.size == 2 -> VOWEL_COMBO[pair(first, jamo[1])]?.let { Syllable(null, it, null, 0) }
                else -> null
            }
        }
        if (first !in CHO) return null
        if (jamo.size == 1) return Syllable(first, null, null, 0)

        val v1 = jamo[1]
        if (!isVowel(v1)) return null
        var jung = v1
        var idx = 2
        if (jamo.size > 2) {
            val combo = VOWEL_COMBO[pair(v1, jamo[2])]
            if (combo != null) {
                jung = combo
                idx = 3
            }
        }

        return when (jamo.size - idx) {
            0 -> Syllable(first, jung, null, 0)
            1 -> {
                val t = jamo[idx]
                if (t in SINGLE_JONG) Syllable(first, jung, t, 1) else null
            }
            2 -> {
                val t = JONG_COMBO[pair(jamo[idx], jamo[idx + 1])]
                if (t != null) Syllable(first, jung, t, 2) else null
            }
            else -> null
        }
    }

    private fun render(jamo: List<Char>): String {
        val s = parse(jamo) ?: return jamo.joinToString("")
        if (s.cho == null) return (s.jung ?: ' ').toString()
        if (s.jung == null) return s.cho.toString()
        val c = CHO.indexOf(s.cho)
        val v = JUNG.indexOf(s.jung)
        val t = if (s.jong == null) 0 else JONG.indexOf(s.jong)
        return (0xAC00 + (c * 21 + v) * 28 + t).toChar().toString()
    }

    private fun isVowel(c: Char) = c in 'ㅏ'..'ㅣ'

    private fun pair(a: Char, b: Char) = "$a$b"

    private companion object {
        const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        // Index 0 is "no final consonant".
        const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        const val SINGLE_JONG = "ㄱㄲㄴㄷㄹㅁㅂㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        val VOWEL_COMBO = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
            "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
            "ㅡㅣ" to 'ㅢ',
        )

        val JONG_COMBO = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
            "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
            "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
            "ㅂㅅ" to 'ㅄ',
        )
    }
}

/** Maps the Android keycode of a letter key to its dubeolsik jamo. */
object HangulKeyMap {
    // a..z
    private const val NORMAL = "ㅁㅠㅊㅇㄷㄹㅎㅗㅑㅓㅏㅣㅡㅜㅐㅔㅂㄱㄴㅅㅕㅍㅈㅌㅛㅋ"

    fun jamoFor(keyCode: Int, shift: Boolean): Char? {
        if (keyCode < KeyEvent.KEYCODE_A || keyCode > KeyEvent.KEYCODE_Z) return null
        val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
        if (shift) {
            when (letter) {
                'q' -> return 'ㅃ'
                'w' -> return 'ㅉ'
                'e' -> return 'ㄸ'
                'r' -> return 'ㄲ'
                't' -> return 'ㅆ'
                'o' -> return 'ㅒ'
                'p' -> return 'ㅖ'
            }
        }
        return NORMAL[letter - 'a']
    }
}
