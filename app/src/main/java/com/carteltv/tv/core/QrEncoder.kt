package com.carteltv.tv.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimal in-house QR Code encoder (byte mode, ECC level L, versions 1-5).
 *
 * Zero external dependencies — implements Reed-Solomon over GF(256), finder/alignment
 * patterns, timing, dark module, format info and mask selection. Covers up to 106 bytes,
 * which is enough for LAN companion pairing URLs. Returns null for longer payloads.
 */
object QrEncoder {

    private val CAPACITY_BYTES = intArrayOf(0, 17, 32, 53, 78, 106)
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
    )
    private val ECC_COUNT = intArrayOf(0, 7, 10, 15, 20, 26)
    private val DATA_CODEWORDS = intArrayOf(0, 19, 34, 55, 80, 108)

    // GF(256) tables (polynomial 0x11D)
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = (x shl 1) xor if (x and 0x80 != 0) 0x11D else 0
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return EXP[LOG[a] + LOG[b]]
    }

    private fun alphaPow(n: Int): Int = EXP[n % 255]

    private fun genPoly(n: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until n) {
            val ng = IntArray(g.size + 1)
            for (j in g.indices) {
                ng[j] = ng[j] xor gfMul(g[j], alphaPow(i))
                ng[j + 1] = ng[j + 1] xor g[j]
            }
            g = ng
        }
        return g
    }

    private fun rsEncode(data: IntArray, nEcc: Int): IntArray {
        val gen = genPoly(nEcc).reversedArray()
        val msg = data.copyOf() + IntArray(nEcc)
        for (i in data.indices) {
            val coef = msg[i] and 0xFF
            if (coef != 0) {
                for (j in 1..nEcc) {
                    msg[i + j] = (msg[i + j] xor gfMul(gen[j], coef)) and 0xFF
                }
            }
        }
        return msg.copyOfRange(data.size, msg.size)
    }

    /**
     * Encode [text] into a QR matrix (true = dark module). Returns null if too long
     * or on any internal failure.
     */
    fun encode(text: String): Array<BooleanArray>? = runCatching {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        var version = -1
        for (v in 1..5) {
            if (bytes.size <= CAPACITY_BYTES[v]) { version = v; break }
        }
        if (version == -1) return null

        val size = 4 * version + 17
        val dataCap = DATA_CODEWORDS[version]
        val nEcc = ECC_COUNT[version]

        val bits = mutableListOf<Int>()
        fun addBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        addBits(0b0100, 4)                 // byte mode
        addBits(bytes.size, 8)              // char count
        for (b in bytes) addBits(b.toInt() and 0xFF, 8)
        // terminator
        val term = minOf(4, dataCap * 8 - bits.size)
        repeat(term) { bits.add(0) }
        // pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)
        val codewords = mutableListOf<Int>()
        var i = 0
        while (i < bits.size) {
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or bits[i + k]
            codewords.add(v)
            i += 8
        }
        // pad codewords
        var padToggle = false
        while (codewords.size < dataCap) {
            codewords.add(if (padToggle) 0x11 else 0xEC)
            padToggle = !padToggle
        }
        val dataArr = codewords.map { it and 0xFF }.toIntArray()
        val ecc = rsEncode(dataArr, nEcc)
        val finalCw = dataArr + ecc

        val m = Array(size) { IntArray(size) { -1 } } // -1 unset, 0 light, 1 dark
        val isFunc = Array(size) { BooleanArray(size) }

        fun setModule(r: Int, c: Int, dark: Boolean, func: Boolean = true) {
            if (r in 0 until size && c in 0 until size) {
                m[r][c] = if (dark) 1 else 0
                if (func) isFunc[r][c] = true
            }
        }

        // Finder patterns
        fun finder(r0: Int, c0: Int) {
            for (dr in 0..6) for (dc in 0..6) {
                val onEdge = dr == 0 || dr == 6 || dc == 0 || dc == 6
                val inner = dr in 2..4 && dc in 2..4
                setModule(r0 + dr, c0 + dc, onEdge || inner)
            }
            // separator (light) around finder
            for (dr in -1..7) for (dc in -1..7) {
                val rr = r0 + dr; val cc = c0 + dc
                if (rr in 0 until size && cc in 0 until size) {
                    if (dr < 0 || dr > 6 || dc < 0 || dc > 6) {
                        if (m[rr][cc] == -1) { m[rr][cc] = 0; isFunc[rr][cc] = true }
                    }
                }
            }
        }
        finder(0, 0)
        finder(0, size - 7)
        finder(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            setModule(6, i, i % 2 == 0)
            setModule(i, 6, i % 2 == 0)
        }

        // Alignment patterns
        val centers = ALIGNMENT[version]
        for (r in centers) for (c in centers) {
            // skip if overlapping finder
            if (r < 9 && c < 9) continue
            if (r < 9 && c > size - 10) continue
            if (r > size - 10 && c < 9) continue
            for (dr in -2..2) for (dc in -2..2) {
                val onEdge = kotlin.math.max(kotlin.math.abs(dr), kotlin.math.abs(dc)) == 2
                val center = dr == 0 && dc == 0
                setModule(r + dr, c + dc, onEdge || center)
            }
        }

        // Dark module
        setModule(4 * version + 9, 8, true)

        // Version info (v7+) - skip for 1-5

        // Format info placeholders (will fill after mask chosen) - mark positions as func
        val formatPositions = mutableListOf<Pair<Int, Int>>()
        // two L-shaped strips
        for (i in 0..5) { formatPositions.add(8 to i); formatPositions.add(i to 8) }
        formatPositions.add(8 to 7)
        formatPositions.add(7 to 8)
        formatPositions.add(8 to 8) // not used but reserved
        for (i in size - 8 until size) { formatPositions.add(8 to i); formatPositions.add(i to 8) }
        for (p in formatPositions) {
            if (p.first in 0 until size && p.second in 0 until size) {
                if (m[p.first][p.second] == -1) { m[p.first][p.second] = 0; isFunc[p.first][p.second] = true }
            }
        }

        val dataBits = mutableListOf<Int>()
        for (cw in finalCw) for (k in 7 downTo 0) dataBits.add((cw ushr k) and 1)

        var bitIdx = 0
        var col = size - 1
        var up = true
        while (col >= 0) {
            if (col == 6) col -= 1
            for (step in 0 until size) {
                val r = if (up) size - 1 - step else step
                for (cc in intArrayOf(col, col - 1)) {
                    if (cc < 0) continue
                    if (m[r][cc] != -1) continue
                    val bit = if (bitIdx < dataBits.size) dataBits[bitIdx++] else 0
                    m[r][cc] = bit
                    // not marked func (data)
                }
            }
            col -= 2
            up = !up
        }

        fun maskBit(mask: Int, r: Int, c: Int): Boolean {
            return when (mask) {
                0 -> (r + c) % 2 == 0
                1 -> r % 2 == 0
                2 -> c % 3 == 0
                3 -> (r + c) % 3 == 0
                4 -> (r / 2 + c / 3) % 2 == 0
                5 -> (r * c) % 2 + (r * c) % 3 == 0
                6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                7 -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
                else -> false
            }
        }

        fun penalty(mat: Array<IntArray>): Int {
            var p = 0
            // rule 1: consecutive same color
            for (r in 0 until size) {
                var run = 1
                for (c in 1 until size) {
                    if (mat[r][c] == mat[r][c - 1]) run++ else { if (run >= 5) p += 3 + (run - 5); run = 1 }
                }
                if (run >= 5) p += 3 + (run - 5)
            }
            for (c in 0 until size) {
                var run = 1
                for (r in 1 until size) {
                    if (mat[r][c] == mat[r - 1][c]) run++ else { if (run >= 5) p += 3 + (run - 5); run = 1 }
                }
                if (run >= 5) p += 3 + (run - 5)
            }
            // rule 2: 2x2 blocks
            for (r in 0 until size - 1) for (c in 0 until size - 1) {
                val v = mat[r][c]
                if (v == mat[r][c + 1] && v == mat[r + 1][c] && v == mat[r + 1][c + 1]) p += 3
            }
            // rule 4: dark ratio
            var dark = 0
            for (r in 0 until size) for (c in 0 until size) if (mat[r][c] == 1) dark++
            val total = size * size
            val pct = dark * 100 / total
            val dev = kotlin.math.abs(pct - 50) / 5
            p += dev * 10
            return p
        }

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val cand = Array(size) { r -> m[r].copyOf() }
            for (r in 0 until size) for (c in 0 until size) {
                if (isFunc[r][c]) continue
                if (m[r][c] == -1) continue
                if (maskBit(mask, r, c)) cand[r][c] = cand[r][c] xor 1
            }
            val pn = penalty(cand)
            if (pn < bestPenalty) { bestPenalty = pn; bestMask = mask }
        }

        // Apply best mask to data modules
        for (r in 0 until size) for (c in 0 until size) {
            if (isFunc[r][c]) continue
            if (m[r][c] == -1) continue
            if (maskBit(bestMask, r, c)) m[r][c] = m[r][c] xor 1
        }

        val eccLevel = 1 // L -> 01? format: (eccLevel << 3) | mask, eccLevel: L=1, M=0, Q=3, H=2
        // Actually QR format info: ECC level bits: L=01, M=00, Q=11, H=10. So L=01.
        val formatData = (0b01 shl 3) or bestMask
        // BCH encode (15,5)
        var fmt = formatData shl 10
        val genFmt = 0b10100110111 // generator for format info
        for (i in 4 downTo 0) {
            if ((fmt ushr (i + 10)) and 1 == 1) fmt = fmt xor (genFmt shl i)
        }
        fmt = ((formatData shl 10) or fmt) xor 0b101010000010010

        val fmtBits = IntArray(15) { (fmt ushr it) and 1 }
        val pos1 = listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8,
            7 to 8, 8 to 8, 8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        )
        val pos2 = (0 until 8).map { 8 to (size - 1 - it) } + (8 until 15).map { (size - 15 + it) to 8 }
        for (i in 0 until 15) {
            val (r, c) = pos1[i]
            m[r][c] = fmtBits[i]
            isFunc[r][c] = true
        }
        for (i in 0 until 15) {
            val (r, c) = pos2[i]
            m[r][c] = fmtBits[i]
            isFunc[r][c] = true
        }

        Array(size) { r -> BooleanArray(size) { c -> m[r][c] == 1 } }
    }.getOrNull()
}