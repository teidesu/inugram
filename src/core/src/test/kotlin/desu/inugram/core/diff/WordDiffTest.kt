package desu.inugram.core.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    // renders the diff as a marked-up string: `[-deleted-]` and `[+inserted+]`
    private fun diff(oldText: String, newText: String): String {
        val sb = StringBuilder()
        for (op in WordDiff.compute(oldText, newText)) {
            when (op.kind) {
                DiffKind.EQUAL -> sb.append(oldText, op.oldStart, op.oldStart + op.length)
                DiffKind.DELETE -> sb.append("[-")
                    .append(oldText, op.oldStart, op.oldStart + op.length)
                    .append("-]")

                DiffKind.INSERT -> sb.append("[+")
                    .append(newText, op.newStart, op.newStart + op.length)
                    .append("+]")
            }
        }
        return sb.toString()
    }

    @Test
    fun identical() {
        assertEquals("hello", diff("hello", "hello"))
    }

    // single-direction edits inside a word — char-level via prefix/suffix peel
    @Test
    fun simple_insert() {
        assertEquals("dr[+i+]ve", diff("drve", "drive"))
    }

    @Test
    fun simple_delete() {
        assertEquals("test[-s-]", diff("tests", "test"))
    }

    @Test
    fun morphology_ending() {
        assertEquals("run[-ning-][+s+]", diff("running", "runs"))
    }

    @Test
    fun internal_deletes() {
        assertEquals("ab[-X-]cd[-Y-]ef", diff("abXcdYef", "abcdef"))
    }

    // permutation-like edits (mixed-direction anchor) — block, no peel
    @Test
    fun permutation_short_words() {
        assertEquals("[-почти-][+прости+]", diff("почти", "прости"))
    }

    @Test
    fun permutation_swap() {
        assertEquals("[-принято-][+приятно+]", diff("принято", "приятно"))
    }

    @Test
    fun unrelated_words() {
        // tiny shared suffix shouldn't trigger peel
        assertEquals("[-конечно-][+временно+]", diff("конечно", "временно"))
    }

    // word-level swaps
    @Test
    fun single_word_swap() {
        assertEquals("the [-cat-][+dog+] is happy", diff("the cat is happy", "the dog is happy"))
    }

    @Test
    fun merge_adjacent_word_changes() {
        // multi-word change: should merge across whitespace bridges
        assertEquals(
            "i went [-shopping there-][+to a mall+]",
            diff("i went shopping there", "i went to a mall"),
        )
    }

    @Test
    fun keep_intra_word_anchor_across_whitespace() {
        // running/runs has intra-word EQ "run", so the merge across " " must NOT pull in fast/slow
        assertEquals(
            "run[-ning-][+s+] [-fast-][+slow+]",
            diff("running fast", "runs slow"),
        )
    }

    @Test
    fun long_shared_context_stays_separate() {
        // EQ between word changes is too long to absorb
        assertEquals(
            "the [-cat-][+dog+] is [-happy-][+grumpy+]",
            diff("the cat is happy", "the dog is grumpy"),
        )
    }

    // whitespace at middle edges → block (avoids invisible leading-space deletion)
    @Test
    fun whitespace_mid_edge_forces_block() {
        assertEquals(
            "в более холодны[-е-][+х+] [-края ехать-][+краях+]",
            diff("в более холодные края ехать", "в более холодных краях"),
        )
    }

    // edge cases
    @Test
    fun full_replace() {
        assertEquals("[-abc-][+xyz+]", diff("abc", "xyz"))
    }

    @Test
    fun pure_insertion() {
        assertEquals("hello[+ world+]", diff("hello", "hello world"))
    }

    @Test
    fun pure_deletion() {
        assertEquals("hello[- world-]", diff("hello world", "hello"))
    }

    @Test
    fun empty_old_text() {
        assertEquals("[+hello+]", diff("", "hello"))
    }

    @Test
    fun empty_new_text() {
        assertEquals("[-hello-]", diff("hello", ""))
    }

    @Test
    fun both_empty() {
        assertEquals("", diff("", ""))
    }

    // single-char typo inside a word with whitespace context — peel + char-diff middle
    @Test
    fun typo_inside_word() {
        assertEquals("hell[-o-][+p+] world", diff("hello world", "hellp world"))
    }

    // digits live in the same token class as letters
    @Test
    fun numeric_token_change() {
        assertEquals("v1.[-2-][+3+]", diff("v1.2", "v1.3"))
    }

    // punctuation tokenizes as single-char tokens
    @Test
    fun punctuation_change() {
        assertEquals("hi[-!-][+?+]", diff("hi!", "hi?"))
    }

    // separate changes through a non-whitespace EQ stay separate (no merge)
    @Test
    fun separate_changes_across_word_eq() {
        assertEquals(
            "a [-x-][+m+] b [-y-][+n+] c",
            diff("a x b y c", "a m b n c"),
        )
    }

    // 𝑥 (U+1D465) is a supplementary char (UTF-16 surrogate pair) — must not be split mid-pair
    @Test
    fun supplementary_char_swap() {
        assertEquals("[-x-][+𝑥+]", diff("x", "𝑥"))
    }

    @Test
    fun supplementary_char_inside_word() {
        assertEquals(
            "hi [-𝑥-][+𝑦+] world",
            diff("hi 𝑥 world", "hi 𝑦 world"),
        )
    }
}
