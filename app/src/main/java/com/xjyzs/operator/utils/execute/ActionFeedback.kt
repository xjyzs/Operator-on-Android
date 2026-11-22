package com.xjyzs.operator.utils.execute

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.graphics.scale
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.ImageMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal class Thumb(val w: Int, val h: Int, val rgb: IntArray, val g: IntArray)

private class FrameDiff(
    val changed: Int, val total: Int, val box: IntArray? // l,t,r,b，0-999
)

private const val THUMB_PX_PER_DP = 0.4f
private const val THUMB_MIN_W = 64
private const val THUMB_MAX_W = 640
private const val BLK = 4 // 4x4 缩略图像素为一块
private const val PIX_THR = 20 // RGB 单通道差阈值
private const val BLK_MIN = 3 // 一块里至少 3 个像素变化才算该块变化
private const val NOISE_BLOCKS = 2   // 固定块数噪声阈值
private const val TAP_REPEAT = 40 // 判定相近位置的距离

internal fun calcThumbWidth(origW: Int, dpi: Int): Int {
    val safeDpi = dpi.takeIf { it > 0 } ?: 480
    val screenshotPxPerDp = ((safeDpi / 160f) * (safeDpi / 720f)).coerceAtLeast(0.5f)
    val widthDp = origW / screenshotPxPerDp
    val minW = minOf(THUMB_MIN_W, origW)
    val maxW = minOf(THUMB_MAX_W, origW)
    return (widthDp * THUMB_PX_PER_DP).roundToInt().coerceIn(minW, maxW)
}

object ActionFeedback {
    private val deadTaps = ArrayList<ImageMarker.Tap>()
    private val deadDirs = LinkedHashSet<Char>()

    /** 新任务/切换或清空会话时调用 */
    fun reset() {
        deadTaps.clear(); deadDirs.clear()
    }

    suspend fun analyze(
        prevDataUrl: String?, curDataUrl: String?, marker: ImageMarker?
    ): String = withContext(Dispatchers.Default) {
        if (prevDataUrl == null || curDataUrl == null || marker == null) return@withContext ""
        val p = decodeThumb(prevDataUrl) ?: return@withContext ""
        val cur = decodeThumb(curDataUrl) ?: return@withContext ""
        analyzeThumbs(p, cur, marker)
    }

    internal fun analyzeThumbs(p: Thumb, cur: Thumb, marker: ImageMarker): String {
        val d = diff(p, cur) ?: return "" // 分辨率或方向改变

        val dead = d.changed <= NOISE_BLOCKS
        val pct = d.changed * 100f / d.total
        val region = d.box?.let { "(${it[0]},${it[1]})-(${it[2]},${it[3]})" }.orEmpty()

        return when (marker) {
            is ImageMarker.Tap -> {
                if (dead) {
                    deadTaps.add(marker)
                    val n = deadTaps.count { near(it, marker) }
                    buildString {
                        append("warning:点击(${marker.x.roundToInt()},${marker.y.roundToInt()})后画面无变化,请根据准星偏移重新定位\n")
                        if (n >= 2) append("warning:相近位置已连续${n}次点击无效\n")
                    }
                } else {
                    deadTaps.clear()
                    deadDirs.clear()
                    if (pct > 10f) return ""
                    String.format(Locale.US, "画面变化%.1f%%，变化区域%s\n", pct, region)
                }
            }

            is ImageMarker.Swipe -> swipeFeedback(p, cur, marker, dead)
        }
    }

    private fun swipeFeedback(
        a: Thumb, b: Thumb, s: ImageMarker.Swipe, dead: Boolean
    ): String {
        val dx = s.x2 - s.x1
        val dy = s.y2 - s.y1
        val vertical = abs(dy) >= abs(dx)
        val dir = if (vertical) (if (dy < 0) '上' else '下') else (if (dx < 0) '左' else '右')
        val axisLen = if (vertical) a.h else a.w
        val dist = if (vertical) abs(dy) else abs(dx)
        val expectedPx = (dist * axisLen / 999f).roundToInt()
        val expectedPct = dist / 9.99f

        fun untried() = "上下左右".filter { it !in deadDirs }

        if (dead) {
            deadDirs.add(dir)
            return "warning:${dir}滑后画面无变化,未尝试方向:${untried()};也可在其他区域滑动\n"
        }
        val expectedSign = if (vertical) (if (dy < 0) -1 else 1) else (if (dx < 0) -1 else 1)
        val shift = abs(estimateShift(a, b, vertical, expectedPx, expectedSign))
        if (shift == 0) {
            deadTaps.clear()
            return "" // 画面变化，不是平移
        }
        val movedPct = shift * 100f / axisLen
        return if (abs(expectedPct - movedPct) < expectedPct * 0.1f) ""
        else {
            val successful = movedPct > expectedPct * 0.3f
            if (successful) {
                deadDirs.clear()
                deadTaps.clear()
            } else deadDirs.add(dir)
            String.format(
                Locale.US, "warning:内容仅平移%.0f%%(滑动%.0f%%)\n", movedPct, expectedPct
            )
        }
    }

    private fun near(a: ImageMarker.Tap, b: ImageMarker.Tap) =
        abs(a.x - b.x) <= TAP_REPEAT && abs(a.y - b.y) <= TAP_REPEAT


    private fun decodeThumb(dataUrl: String): Thumb? = try {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val thumbW = calcThumbWidth(bounds.outWidth, DisplayMetricsStore.densityDpi)
        val sampleSize = Integer.highestOneBit((bounds.outWidth / thumbW).coerceAtLeast(1))
        val full = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null
        val th = (thumbW.toLong() * full.height / full.width).toInt().coerceAtLeast(8)
        val s = full.scale(thumbW, th)
        val px = IntArray(thumbW * th)
        s.getPixels(px, 0, thumbW, 0, 0, thumbW, th)
        if (s !== full) s.recycle()
        full.recycle()
        Thumb(
            w = thumbW, h = th, rgb = px, g = IntArray(px.size) {
                val c = px[it]
                (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8
            })
    } catch (_: Exception) {
        null
    }

    private fun diff(a: Thumb, b: Thumb): FrameDiff? {
        if (a.w != b.w || a.h != b.h) return null
        val bw = a.w / BLK
        val bh = a.h / BLK
        var cnt = 0
        var l = Int.MAX_VALUE;
        var t = Int.MAX_VALUE;
        var r = -1;
        var bt = -1
        for (by in 0 until bh) for (bx in 0 until bw) {
            var c = 0
            for (y in 0 until BLK) for (x in 0 until BLK) {
                val i = (by * BLK + y) * a.w + bx * BLK + x
                val p1 = a.rgb[i]
                val p2 = b.rgb[i]
                if (p1 == p2) continue
                val dr = abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
                val dg = abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
                val db = abs((p1 and 0xFF) - (p2 and 0xFF))
                if (dr > PIX_THR || dg > PIX_THR || db > PIX_THR) c++
            }
            if (c >= BLK_MIN) {
                cnt++
                if (bx < l) l = bx; if (bx > r) r = bx
                if (by < t) t = by; if (by > bt) bt = by
            }
        }
        val box = if (cnt == 0) null else intArrayOf(
            l * BLK * 999 / a.w,
            t * BLK * 999 / a.h,
            ((r + 1) * BLK * 999 / a.w).coerceAtMost(999),
            ((bt + 1) * BLK * 999 / a.h).coerceAtMost(999)
        )
        return FrameDiff(cnt, bh * bw, box)
    }

    /** 沿滑动轴估计内容平移（缩略图像素，带符号），未检出返回 0 */
    private fun estimateShift(
        a: Thumb, b: Thumb, vertical: Boolean, expected: Int, expectedSign: Int = 0
    ): Int {
        val w = a.w
        val h = a.h
        if (w < 6 || h < 8) return 0
        val len = if (vertical) h else w
        val maxD = minOf(expected * 3 / 2 + 2, (len * 3) / 4)
        if (maxD <= 0) return 0

        val y0 = h.coerceIn(1, h - 3)
        val nPx = w * h

        val rawAct = BooleanArray(nPx)
        var rawCount = 0
        for (y in y0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val p1 = a.rgb[i]
                val p2 = b.rgb[i]
                if (p1 == p2) continue
                val dr = abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
                val dg = abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
                val db = abs((p1 and 0xFF) - (p2 and 0xFF))
                if (dr > 10 || dg > 10 || db > 10) {
                    rawAct[i] = true
                    rawCount++
                }
            }
        }
        if (rawCount == 0) return 0

        val actMask = BooleanArray(nPx)
        for (y in y0 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (rawAct[i] || rawAct[i - 1] || rawAct[i + 1] || rawAct[i - w] || rawAct[i + w]) {
                    actMask[i] = true
                }
            }
        }

        val (gxa, gya) = sobelGrad(a.g, w, h, y0)
        val (gxb, gyb) = sobelGrad(b.g, w, h, y0)

        val stage1 = estimateGlobalAb(
            a.g, b.g, w, h, y0, maxD, vertical, expectedSign, actMask, gxa, gya, gxb, gyb
        )
        if (stage1 != 0) return stage1

        return estimateGlobalDiffGrad(
            w, h, y0, maxD, vertical, expectedSign, actMask, gxa, gya, gxb, gyb
        )
    }

    private fun sobelGrad(g: IntArray, w: Int, h: Int, y0: Int): Pair<IntArray, IntArray> {
        val gx = IntArray(w * h)
        val gy = IntArray(w * h)
        val ys = y0.coerceAtLeast(1)
        val ye = h - 1
        for (y in ys until ye) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                gx[r1 + x] =
                    (g[r0 + x + 1] + 2 * g[r1 + x + 1] + g[r2 + x + 1]) - (g[r0 + x - 1] + 2 * g[r1 + x - 1] + g[r2 + x - 1])
                gy[r1 + x] =
                    (g[r2 + x - 1] + 2 * g[r2 + x] + g[r2 + x + 1]) - (g[r0 + x - 1] + 2 * g[r0 + x] + g[r0 + x + 1])
            }
        }
        return gx to gy
    }

    private fun evalGlobalAb(
        w: Int,
        h: Int,
        y0: Int,
        d: Int,
        vertical: Boolean,
        actMask: BooleanArray,
        gxa: IntArray,
        gya: IntArray,
        gxb: IntArray,
        gyb: IntArray,
        minEnergySum: Double,
        minEdgePixels: Int
    ): Double {
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        var edgeCnt = 0
        val yEnd = h - 1
        val xEnd = w - 1

        val ys = if (vertical) maxOf(y0, y0 - d) else y0
        val ye = if (vertical) minOf(yEnd, yEnd - d) else yEnd
        val xs = if (vertical) 1 else maxOf(1, 1 - d)
        val xe = if (vertical) xEnd else minOf(xEnd, xEnd - d)
        if (ys >= ye || xs >= xe) return -1.0

        for (y in ys until ye) {
            val yb = if (vertical) y + d else y
            val ra = y * w
            val rb = yb * w
            for (x in xs until xe) {
                val ia = ra + x
                if (!actMask[ia]) continue
                val xb = if (vertical) x else x + d
                val ib = rb + xb
                if (!actMask[ib]) continue

                val ux = gxa[ia]
                val uy = gya[ia]
                val vx = gxb[ib]
                val vy = gyb[ib]
                if ((ux or uy or vx or vy) == 0) continue

                dot += ux.toDouble() * vx + uy.toDouble() * vy
                ea += ux.toDouble() * ux + uy.toDouble() * uy
                eb += vx.toDouble() * vx + vy.toDouble() * vy
                if (abs(ux) + abs(uy) > 16 || abs(vx) + abs(vy) > 16) edgeCnt++
            }
        }
        if (ea + eb < minEnergySum || edgeCnt < minEdgePixels) return -1.0
        return (2.0 * dot) / (ea + eb)
    }

    private fun estimateGlobalAb(
        ga: IntArray,
        gb: IntArray,
        w: Int,
        h: Int,
        y0: Int,
        maxD: Int,
        vertical: Boolean,
        expectedSign: Int,
        actMask: BooleanArray,
        gxa: IntArray,
        gya: IntArray,
        gxb: IntArray,
        gyb: IntArray
    ): Int {
        var totalEA = 0.0
        var totalEB = 0.0
        for (y in y0 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (!actMask[i]) continue
                totalEA += gxa[i].toDouble() * gxa[i] + gya[i].toDouble() * gya[i]
                totalEB += gxb[i].toDouble() * gxb[i] + gyb[i].toDouble() * gyb[i]
            }
        }
        val totalRef = 0.5 * (totalEA + totalEB)
        if (totalRef <= 0.0) return 0

        val minEnergySum = totalRef * 0.14 // 至少 7% 活动边缘能量参与重叠匹配
        val minEdgePixels = 20

        val s0 = evalGlobalAb(
            w, h, y0, 0, vertical, actMask, gxa, gya, gxb, gyb, minEnergySum * 0.25, 8
        ).coerceAtLeast(0.0)

        // 小图全搜；大图2x2均值降采样粗搜出峰，再全分辨率精搜
        val candidates = ArrayList<Int>()
        if (w * h <= 80_000) {
            for (d in -maxD..maxD) if (d != 0) candidates.add(d)
        } else {
            val w2 = w / 2
            val h2 = h / 2
            val y02 = (y0 / 2).coerceIn(1, h2 - 3)
            val maxD2 = (maxD / 2 + 1).coerceAtMost((if (vertical) h2 else w2) * 3 / 4)
            val g2a = IntArray(w2 * h2)
            val g2b = IntArray(w2 * h2)
            val act2 = BooleanArray(w2 * h2)

            for (y2 in 0 until h2) {
                val y = y2 * 2
                val r0 = y * w
                val r1 = (y + 1) * w
                val r2 = y2 * w2
                for (x2 in 0 until w2) {
                    val x = x2 * 2
                    val i0 = r0 + x
                    val i1 = r1 + x
                    val idx2 = r2 + x2
                    g2a[idx2] = (ga[i0] + ga[i0 + 1] + ga[i1] + ga[i1 + 1]) shr 2
                    g2b[idx2] = (gb[i0] + gb[i0 + 1] + gb[i1] + gb[i1 + 1]) shr 2
                    act2[idx2] = actMask[i0] || actMask[i0 + 1] || actMask[i1] || actMask[i1 + 1]
                }
            }

            val (gx2a, gy2a) = sobelGrad(g2a, w2, h2, y02)
            val (gx2b, gy2b) = sobelGrad(g2b, w2, h2, y02)
            var ref2A = 0.0
            var ref2B = 0.0
            for (y2 in y02 until h2 - 1) {
                val r2 = y2 * w2
                for (x2 in 1 until w2 - 1) {
                    val i2 = r2 + x2
                    if (!act2[i2]) continue
                    ref2A += gx2a[i2].toDouble() * gx2a[i2] + gy2a[i2].toDouble() * gy2a[i2]
                    ref2B += gx2b[i2].toDouble() * gx2b[i2] + gy2b[i2].toDouble() * gy2b[i2]
                }
            }
            val minE2 = 0.5 * (ref2A + ref2B) * 0.14
            val scores2 = DoubleArray(2 * maxD2 + 1) { -1.0 }
            for (d2 in -maxD2..maxD2) {
                if (d2 == 0) continue
                scores2[d2 + maxD2] = evalGlobalAb(
                    w2, h2, y02, d2, vertical, act2, gx2a, gy2a, gx2b, gy2b, minE2, 10
                )
            }

            // 选出前 4 个互相间隔 >= 3 的粗峰，以及小位移 [-6..6]
            val peaks = ArrayList<Int>()
            repeat(4) {
                var pD = 0
                var pS = 0.12
                for (d2 in -maxD2..maxD2) {
                    if (d2 == 0) continue
                    if (peaks.any { abs(it - d2) < 3 }) continue
                    val s = scores2[d2 + maxD2]
                    if (s > pS) {
                        pS = s
                        pD = d2
                    }
                }
                if (pD != 0) peaks.add(pD)
            }

            val added = BooleanArray(2 * maxD + 1)
            fun addCand(d: Int) {
                if (d != 0 && d in -maxD..maxD && !added[d + maxD]) {
                    added[d + maxD] = true
                    candidates.add(d)
                }
            }
            for (d in -6..6) addCand(d)
            for (p in peaks) {
                val base = p * 2
                for (k in -3..3) addCand(base + k)
            }
        }

        var bestD = 0
        var bestSim = -1.0
        for (d in candidates) {
            val s = evalGlobalAb(
                w, h, y0, d, vertical, actMask, gxa, gya, gxb, gyb, minEnergySum, minEdgePixels
            )
            val better = if (expectedSign != 0 && abs(s - bestSim) < 0.03) {
                (d * expectedSign > 0) && bestD * expectedSign <= 0
            } else {
                s > bestSim
            }
            if (better) {
                bestSim = s
                bestD = d
            }
        }

        if (bestD != 0 && bestSim >= 0.42 && (1.0 - bestSim) < (1.0 - s0) * 0.65 && bestSim > s0 + 0.10) {
            return bestD
        }
        return 0
    }

    private fun estimateGlobalDiffGrad(
        w: Int,
        h: Int,
        y0: Int,
        maxD: Int,
        vertical: Boolean,
        expectedSign: Int,
        actMask: BooleanArray,
        gxa: IntArray,
        gya: IntArray,
        gxb: IntArray,
        gyb: IntArray
    ): Int {
        val nPx = w * h
        val dx = IntArray(nPx)
        val dy = IntArray(nPx)
        val eDiff = DoubleArray(nPx)
        val step = if (nPx > 80_000) 2 else 1

        var sumE = 0.0
        var sumAbsE = 0.0
        var totalDiffEnergy = 0.0
        var maskCount = 0

        for (y in y0 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (!actMask[i]) continue
                maskCount++
                val ddx = gxb[i] - gxa[i]
                val ddy = gyb[i] - gya[i]
                dx[i] = ddx
                dy[i] = ddy
                totalDiffEnergy += ddx.toDouble() * ddx + ddy.toDouble() * ddy

                val ea = gxa[i].toDouble() * gxa[i] + gya[i].toDouble() * gya[i]
                val eb = gxb[i].toDouble() * gxb[i] + gyb[i].toDouble() * gyb[i]
                val ed = eb - ea
                eDiff[i] = ed
                sumE += ed
                sumAbsE += abs(ed)
            }
        }

        if (maskCount < 16 || totalDiffEnergy <= 0.0 || sumAbsE <= 0.0) return 0
        // 静态弹窗弹出/关闭会导致单边边缘能量剧增或剧减，直接拒绝
        if (abs(sumE) / sumAbsE > 0.75) return 0

        val minOverlapEnergy = totalDiffEnergy * 0.12
        var bestD = 0
        var bestSd = 0.0
        var bestFlip = -1.0

        val yEnd = h - 1
        val xEnd = w - 1

        for (d in 1..maxD) {
            val ys = if (vertical) y0 else y0
            val ye = if (vertical) yEnd - d else yEnd
            val xs = 1
            val xLimit = if (vertical) xEnd else xEnd - d
            if (ys >= ye || xs >= xLimit) break

            var anti = 0.0
            var n1 = 0.0
            var n2 = 0.0
            var flip = 0.0
            var fe1 = 0.0
            var fe2 = 0.0
            var cnt = 0

            var y = ys
            while (y < ye) {
                val yb = if (vertical) y + d else y
                val r1 = y * w
                val r2 = yb * w
                var x = xs
                while (x < xLimit) {
                    val xb = if (vertical) x else x + d
                    val i1 = r1 + x
                    val i2 = r2 + xb
                    if (actMask[i1] && actMask[i2]) {
                        val dx1 = dx[i1].toDouble();
                        val dy1 = dy[i1].toDouble()
                        val dx2 = dx[i2].toDouble();
                        val dy2 = dy[i2].toDouble()
                        if (dx1 != 0.0 || dy1 != 0.0 || dx2 != 0.0 || dy2 != 0.0) {
                            anti -= dx1 * dx2 + dy1 * dy2
                            n1 += dx1 * dx1 + dy1 * dy1
                            n2 += dx2 * dx2 + dy2 * dy2

                            val ed1 = eDiff[i1]
                            val ed2 = eDiff[i2]
                            flip -= ed1 * ed2
                            fe1 += ed1 * ed1
                            fe2 += ed2 * ed2
                            cnt++
                        }
                    }
                    x += step
                }
                y += step
            }

            if (cnt >= 16 && (n1 + n2) * step * step >= minOverlapEnergy && n1 > 0.0 && n2 > 0.0) {
                val sd = anti / (0.5 * (n1 + n2))
                if (sd > bestSd) {
                    bestSd = sd
                    bestD = d
                    bestFlip =
                        if (fe1 > 0.0 && fe2 > 0.0) flip / kotlin.math.sqrt(fe1 * fe2) else 0.0
                }
            }
        }

        if (bestD == 0 || bestSd < 0.25 || bestFlip <= 0.0) return 0
        return if (expectedSign != 0) bestD * expectedSign else bestD
    }
}
