package desu.inugram.core.diff

enum class DiffKind { EQUAL, INSERT, DELETE }

data class DiffRange(
    val kind: DiffKind,
    val oldStart: Int,
    val newStart: Int,
    val length: Int,
)

object WordDiff {
    /**
     * Word-level diff with intra-word char-level refinement.
     *
     * Pipeline:
     *  1. Tokenize (letter/digit runs, whitespace runs, single chars).
     *  2. Myers on tokens.
     *  3. Per adjacent DEL+INS pair, peel char-level prefix/suffix and char-diff the middle
     *     when "clean" (single anchor, single-direction non-EQ ops).
     *  4. Collapse consecutive DEL+INS pairs separated by whitespace EQs into one block,
     *     except when adjacent to an EQ ending/starting with a word char (intra-word continuation).
     *
     * Internally everything works on code-point arrays so supplementary characters
     * (surrogate pairs) are never split. Char offsets are reconstructed at the end.
     */
    fun compute(oldText: String, newText: String): List<DiffRange> {
        val oldCps = toCodePoints(oldText)
        val newCps = toCodePoints(newText)
        val oldOffsets = cpOffsets(oldText, oldCps.size)
        val newOffsets = cpOffsets(newText, newCps.size)
        val cpOps = mergeChangeRuns(
            oldCps,
            coalesce(refineAdjacentEdits(oldCps, newCps, wordLevelDiff(oldCps, newCps))),
        )
        return cpOps.map { r ->
            DiffRange(
                r.kind,
                oldOffsets[r.oldStart],
                newOffsets[r.newStart],
                when (r.kind) {
                    DiffKind.INSERT -> newOffsets[r.newStart + r.length] - newOffsets[r.newStart]
                    else -> oldOffsets[r.oldStart + r.length] - oldOffsets[r.oldStart]
                },
            )
        }
    }

    private fun toCodePoints(s: String): IntArray {
        val out = IntArray(s.codePointCount(0, s.length))
        var i = 0
        var k = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out[k++] = cp
            i += Character.charCount(cp)
        }
        return out
    }

    // cpOffsets[k] = char index where the k-th code point starts; cpOffsets[cpCount] = s.length
    private fun cpOffsets(s: String, cpCount: Int): IntArray {
        val out = IntArray(cpCount + 1)
        var i = 0
        var k = 0
        while (i < s.length) {
            out[k++] = i
            i += Character.charCount(s.codePointAt(i))
        }
        out[cpCount] = s.length
        return out
    }

    private enum class TokenKind { WORD, WHITESPACE, OTHER }

    private fun classify(cp: Int): TokenKind = when {
        Character.isLetterOrDigit(cp) || cp == '_'.code -> TokenKind.WORD
        Character.isWhitespace(cp) -> TokenKind.WHITESPACE
        else -> TokenKind.OTHER
    }

    // packed [start0, end0, start1, end1, ...] — code-point indices
    private fun tokenize(cps: IntArray): IntArray {
        val out = ArrayList<Int>()
        val n = cps.size
        var i = 0
        while (i < n) {
            val k = classify(cps[i])
            var j = i + 1
            if (k != TokenKind.OTHER) {
                while (j < n && classify(cps[j]) == k) j++
            }
            out.add(i); out.add(j)
            i = j
        }
        return out.toIntArray()
    }

    private fun tokenLen(toks: IntArray, idx: Int) = toks[idx * 2 + 1] - toks[idx * 2]
    private fun tokenStart(toks: IntArray, idx: Int) = toks[idx * 2]

    private fun tokensEqual(aCps: IntArray, aToks: IntArray, ai: Int, bCps: IntArray, bToks: IntArray, bi: Int): Boolean {
        val len = tokenLen(aToks, ai)
        if (len != tokenLen(bToks, bi)) return false
        val aStart = tokenStart(aToks, ai)
        val bStart = tokenStart(bToks, bi)
        for (i in 0 until len) {
            if (aCps[aStart + i] != bCps[bStart + i]) return false
        }
        return true
    }

    private fun wordLevelDiff(oldCps: IntArray, newCps: IntArray): List<DiffRange> {
        val aToks = tokenize(oldCps)
        val bToks = tokenize(newCps)
        val n = aToks.size / 2
        val m = bToks.size / 2
        val tokOps = myersGeneric(n, m) { i, j -> tokensEqual(oldCps, aToks, i, newCps, bToks, j) }

        return tokOps.map { op ->
            val srcToks = if (op.kind == DiffKind.INSERT) bToks else aToks
            val srcIdx = if (op.kind == DiffKind.INSERT) op.bIdx else op.aIdx
            val cpLen = (0 until op.count).sumOf { tokenLen(srcToks, srcIdx + it) }
            DiffRange(
                op.kind,
                if (op.aIdx < n) tokenStart(aToks, op.aIdx) else oldCps.size,
                if (op.bIdx < m) tokenStart(bToks, op.bIdx) else newCps.size,
                cpLen,
            )
        }
    }

    private class TokenOp(val kind: DiffKind, val aIdx: Int, val bIdx: Int, val count: Int)

    private inline fun myersGeneric(n: Int, m: Int, eq: (Int, Int) -> Boolean): List<TokenOp> {
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return listOf(TokenOp(DiffKind.INSERT, 0, 0, m))
        if (m == 0) return listOf(TokenOp(DiffKind.DELETE, 0, 0, n))

        val max = n + m
        val v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>(max + 1)

        var found = false
        var d = 0
        while (d <= max) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
                    v[k + 1 + max]
                } else {
                    v[k - 1 + max] + 1
                }
                var y = x - k
                while (x < n && y < m && eq(x, y)) {
                    x++; y++
                }
                v[k + max] = x
                if (x >= n && y >= m) {
                    found = true
                    break
                }
                k += 2
            }
            if (found) break
            d++
        }
        if (!found) return emptyList()

        val raw = ArrayList<TokenOp>()
        var x = n
        var y = m
        for (step in trace.size - 1 downTo 1) {
            val vPrev = trace[step]
            val k = x - y
            val prevK = if (k == -step || (k != step && vPrev[k - 1 + max] < vPrev[k + 1 + max])) k + 1 else k - 1
            val prevX = vPrev[prevK + max]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                raw.add(TokenOp(DiffKind.EQUAL, x - 1, y - 1, 1))
                x--; y--
            }
            if (x == prevX) {
                raw.add(TokenOp(DiffKind.INSERT, x, y - 1, 1))
                y--
            } else {
                raw.add(TokenOp(DiffKind.DELETE, x - 1, y, 1))
                x--
            }
        }
        while (x > 0 && y > 0) {
            raw.add(TokenOp(DiffKind.EQUAL, x - 1, y - 1, 1))
            x--; y--
        }
        raw.reverse()
        return coalesceTokenOps(raw)
    }

    private fun coalesceTokenOps(ops: List<TokenOp>): List<TokenOp> {
        if (ops.isEmpty()) return ops
        val out = ArrayList<TokenOp>()
        var cur = ops[0]
        for (i in 1 until ops.size) {
            val next = ops[i]
            if (next.kind != cur.kind) { out.add(cur); cur = next; continue }
            val advA = cur.kind != DiffKind.INSERT
            val advB = cur.kind != DiffKind.DELETE
            val okA = !advA || next.aIdx == cur.aIdx + cur.count
            val okB = !advB || next.bIdx == cur.bIdx + cur.count
            if (okA && okB) {
                cur = TokenOp(cur.kind, cur.aIdx, cur.bIdx, cur.count + next.count)
            } else {
                out.add(cur); cur = next
            }
        }
        out.add(cur)
        return out
    }

    private fun refineAdjacentEdits(oldCps: IntArray, newCps: IntArray, ops: List<DiffRange>): List<DiffRange> {
        val out = ArrayList<DiffRange>()
        var i = 0
        while (i < ops.size) {
            val cur = ops[i]
            val next = if (i + 1 < ops.size) ops[i + 1] else null
            val isPair = next != null && cur.kind != DiffKind.EQUAL && next.kind != DiffKind.EQUAL && cur.kind != next.kind
            if (!isPair) {
                out.add(cur); i++; continue
            }
            val delOp = if (cur.kind == DiffKind.DELETE) cur else next!!
            val insOp = if (cur.kind == DiffKind.INSERT) cur else next!!
            val delEnd = delOp.oldStart + delOp.length
            val insEnd = insOp.newStart + insOp.length
            val maxCommon = minOf(delOp.length, insOp.length)
            var prefix = 0
            while (prefix < maxCommon && oldCps[delOp.oldStart + prefix] == newCps[insOp.newStart + prefix]) prefix++
            var suffix = 0
            while (suffix < maxCommon - prefix &&
                oldCps[delEnd - 1 - suffix] == newCps[insEnd - 1 - suffix]) suffix++

            val delMidLen = delOp.length - prefix - suffix
            val insMidLen = insOp.length - prefix - suffix
            val midOldStart = delOp.oldStart + prefix
            val midNewStart = insOp.newStart + prefix

            val charOps = if (delMidLen > 0 && insMidLen > 0) {
                myersGeneric(delMidLen, insMidLen) { ii, jj ->
                    oldCps[midOldStart + ii] == newCps[midNewStart + jj]
                }
            } else null
            val midClean = charOps == null || isMiddleClean(charOps, delMidLen, insMidLen)

            // peel must cover ≥40% of the longer side; otherwise shared edges read as coincidence
            // (e.g. happy/grumpy sharing "py", конечно/временно sharing "но").
            val peelTooSmall = (prefix + suffix) * 5 < maxOf(delOp.length, insOp.length) * 2
            val midStartsWs = (delMidLen > 0 && Character.isWhitespace(oldCps[midOldStart])) ||
                (insMidLen > 0 && Character.isWhitespace(newCps[midNewStart]))
            val midEndsWs = (delMidLen > 0 && Character.isWhitespace(oldCps[midOldStart + delMidLen - 1])) ||
                (insMidLen > 0 && Character.isWhitespace(newCps[midNewStart + insMidLen - 1]))
            val messyMidDominatesPeel = !midClean && (prefix + suffix) <= (delMidLen + insMidLen)

            if (peelTooSmall || midStartsWs || midEndsWs || messyMidDominatesPeel) {
                out.add(DiffRange(DiffKind.DELETE, delOp.oldStart, insOp.newStart, delOp.length))
                out.add(DiffRange(DiffKind.INSERT, delOp.oldStart + delOp.length, insOp.newStart, insOp.length))
                i += 2
                continue
            }

            if (prefix > 0) out.add(DiffRange(DiffKind.EQUAL, delOp.oldStart, insOp.newStart, prefix))
            when {
                delMidLen == 0 && insMidLen == 0 -> {}
                delMidLen == 0 -> out.add(DiffRange(DiffKind.INSERT, midOldStart, midNewStart, insMidLen))
                insMidLen == 0 -> out.add(DiffRange(DiffKind.DELETE, midOldStart, midNewStart, delMidLen))
                midClean -> for (op in charOps!!) {
                    out.add(DiffRange(op.kind, midOldStart + op.aIdx, midNewStart + op.bIdx, op.count))
                }
                else -> {
                    out.add(DiffRange(DiffKind.DELETE, midOldStart, midNewStart, delMidLen))
                    out.add(DiffRange(DiffKind.INSERT, midOldStart + delMidLen, midNewStart, insMidLen))
                }
            }
            if (suffix > 0) out.add(DiffRange(DiffKind.EQUAL, delEnd - suffix, insEnd - suffix, suffix))
            i += 2
        }
        return out
    }

    // a char-level result is "clean" enough to keep when:
    //  - ≤1 EQ anchor, covering ≥1/3 of the change (avoids coincidental letter overlap noise),
    //  - all non-EQ ops are the same kind (mixed DEL+INS around an anchor is permutation-style mess).
    private fun isMiddleClean(charOps: List<TokenOp>, delLen: Int, insLen: Int): Boolean {
        val equalOps = charOps.filter { it.kind == DiffKind.EQUAL }
        if (equalOps.size > 1) return false
        if (equalOps.isEmpty()) return true
        if (equalOps[0].count * 3 < maxOf(delLen, insLen)) return false
        return charOps.asSequence().filter { it.kind != DiffKind.EQUAL }.map { it.kind }.distinct().count() <= 1
    }

    // collapses runs of DEL+INS pairs separated by whitespace-only EQs into one DEL+INS block,
    // unless adjacent to an EQ ending/starting with a word char (intra-word continuation).
    private fun mergeChangeRuns(oldCps: IntArray, ops: List<DiffRange>): List<DiffRange> {
        fun isWhitespaceEq(op: DiffRange): Boolean {
            if (op.kind != DiffKind.EQUAL) return false
            for (k in 0 until op.length) {
                if (!Character.isWhitespace(oldCps[op.oldStart + k])) return false
            }
            return true
        }
        fun touchesWordChar(op: DiffRange?, atEnd: Boolean): Boolean {
            if (op == null || op.kind != DiffKind.EQUAL || op.length == 0) return false
            val idx = if (atEnd) op.oldStart + op.length - 1 else op.oldStart
            return Character.isLetterOrDigit(oldCps[idx])
        }

        val out = ArrayList<DiffRange>()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op.kind != DiffKind.DELETE && op.kind != DiffKind.INSERT) {
                out.add(op); i++; continue
            }
            var j = i
            while (j < ops.size) {
                val curJ = ops[j]
                if (curJ.kind != DiffKind.EQUAL) { j++; continue }
                val nextIsChange = j + 1 < ops.size && ops[j + 1].kind != DiffKind.EQUAL
                if (isWhitespaceEq(curJ) && nextIsChange) { j++; continue }
                break
            }
            val run = ops.subList(i, j)
            val nonEq = run.count { it.kind != DiffKind.EQUAL }
            val hasDel = run.any { it.kind == DiffKind.DELETE }
            val hasIns = run.any { it.kind == DiffKind.INSERT }
            val opBefore = if (i > 0) ops[i - 1] else null
            val opAfter = if (j < ops.size) ops[j] else null
            val boundedClean = !touchesWordChar(opBefore, atEnd = true) && !touchesWordChar(opAfter, atEnd = false)
            if (nonEq >= 2 && hasDel && hasIns && boundedClean) {
                val firstDel = run.first { it.kind != DiffKind.INSERT }
                val lastDel = run.last { it.kind != DiffKind.INSERT }
                val firstIns = run.first { it.kind != DiffKind.DELETE }
                val lastIns = run.last { it.kind != DiffKind.DELETE }
                val delEnd = lastDel.oldStart + lastDel.length
                val insEnd = lastIns.newStart + lastIns.length
                out.add(DiffRange(DiffKind.DELETE, firstDel.oldStart, firstIns.newStart, delEnd - firstDel.oldStart))
                out.add(DiffRange(DiffKind.INSERT, delEnd, firstIns.newStart, insEnd - firstIns.newStart))
            } else {
                out.addAll(run)
            }
            i = j
        }
        return out
    }

    private fun coalesce(ops: List<DiffRange>): List<DiffRange> {
        if (ops.isEmpty()) return ops
        val out = ArrayList<DiffRange>()
        var cur = ops[0]
        for (i in 1 until ops.size) {
            val next = ops[i]
            if (next.kind == cur.kind && adjacent(cur, next)) {
                cur = DiffRange(cur.kind, cur.oldStart, cur.newStart, cur.length + next.length)
            } else {
                out.add(cur); cur = next
            }
        }
        out.add(cur)
        return out
    }

    private fun adjacent(cur: DiffRange, next: DiffRange): Boolean = when (cur.kind) {
        DiffKind.EQUAL -> next.oldStart == cur.oldStart + cur.length && next.newStart == cur.newStart + cur.length
        DiffKind.DELETE -> next.oldStart == cur.oldStart + cur.length && next.newStart == cur.newStart
        DiffKind.INSERT -> next.newStart == cur.newStart + cur.length && next.oldStart == cur.oldStart
    }
}
