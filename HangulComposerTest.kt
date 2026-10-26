package com.inputleaf.android.inject

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HangulComposerTest {

    // Types QWERTY letters (uppercase = Shift) and returns the final text.
    private fun type(keys: String): String {
        val c = HangulComposer()
        val out = StringBuilder()
        for (k in keys) {
            val code = KeyEvent.KEYCODE_A + (k.lowercaseChar() - 'a')
            val jamo = HangulKeyMap.jamoFor(code, k.isUpperCase())!!
            out.append(c.input(jamo).committed)
        }
        return out.toString() + c.flush()
    }

    @Test fun `basic words`() {
        assertThat(type("gksrmf")).isEqualTo("한글")
        assertThat(type("dkssudgktpdy")).isEqualTo("안녕하세요")
        assertThat(type("qkqh")).isEqualTo("바보")
    }

    @Test fun `compound vowels`() {
        assertThat(type("dho")).isEqualTo("왜")
        assertThat(type("dnj")).isEqualTo("워")
        assertThat(type("dml")).isEqualTo("의")
        assertThat(type("rhk")).isEqualTo("과")
    }

    @Test fun `compound finals split when a vowel follows`() {
        assertThat(type("rkqt")).isEqualTo("값")
        assertThat(type("ekfrdl")).isEqualTo("닭이")
        assertThat(type("dlfrdj")).isEqualTo("읽어")
        assertThat(type("rhksl")).isEqualTo("과니")
    }

    @Test fun `shifted consonants and vowels`() {
        assertThat(type("Rkrk")).isEqualTo("까가")
        assertThat(type("Tkdl")).isEqualTo("싸이")
    }

    @Test fun `backspace removes one jamo at a time`() {
        val c = HangulComposer()
        "gks".forEach { c.input(HangulKeyMap.jamoFor(KeyEvent.KEYCODE_A + (it - 'a'), false)!!) }
        assertThat(c.backspace()!!.composing).isEqualTo("하")
        assertThat(c.backspace()!!.composing).isEqualTo("ㅎ")
        assertThat(c.backspace()!!.composing).isEqualTo("")
        assertThat(c.backspace()).isNull()
    }

    @Test fun `compound final backspaces to the single final`() {
        val c = HangulComposer()
        "ekfr".forEach { c.input(HangulKeyMap.jamoFor(KeyEvent.KEYCODE_A + (it - 'a'), false)!!) }
        assertThat(c.backspace()!!.composing).isEqualTo("달")
        assertThat(c.backspace()!!.composing).isEqualTo("다")
    }

    @Test fun `flush returns the syllable and clears state`() {
        val c = HangulComposer()
        c.input('ㅎ'); c.input('ㅏ')
        assertThat(c.flush()).isEqualTo("하")
        assertThat(c.isComposing).isFalse()
    }
}
