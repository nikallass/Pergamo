package com.yourname.pdftoolkit.domain.operations

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scanner-like image enhancement.
 *
 * The core idea is background (illumination) normalization: paper is rarely evenly lit, so a
 * photo of a document has grey or yellow shadows instead of white paper. We estimate the local
 * paper colour for every pixel and divide the pixel by it, which turns the paper into pure white
 * while ink stays dark. Because the division is done per channel and the final levels curve is
 * applied on luminance (channels are scaled proportionally), coloured content — stamps,
 * highlighter, photos, coloured pens — keeps its hue instead of being washed out or forced to grey.
 *
 * Pipeline:
 *  1. Estimate the paper colour for every part of the page (see [estimateBackground]), unless
 *     whitening is off — then the photo's own colours are carried through untouched.
 *  2. Divide by that estimate, sampled bilinearly, and mix the result back towards the original
 *     by the whitening strength.
 *  3. Apply a levels curve (black point / white point) on luminance, scaling R/G/B by the same
 *     factor so hue is preserved. This runs whether or not the background was whitened, which
 *     is what makes the black point double as a contrast control for plain photos.
 *  4. Apply a small saturation boost to compensate for the whitening.
 *
 * Everything is plain pixel arithmetic on `IntArray`s — no `Canvas`, no `createScaledBitmap` — so
 * the result does not depend on the platform's rasterizer and can be unit-tested directly.
 */
object ScanEnhancer {

    /** Default whitening strength: a scan is expected to have white paper. */
    const val DEFAULT_WHITEN_STRENGTH = 1f

    /** Default black point. Scans are expected to have properly black ink, not dark grey. */
    const val DEFAULT_BLACK_POINT = 0.5f

    /** Long edge of the downscaled background estimate. */
    private const val BACKGROUND_SIZE = 96

    /** Radius (in thumbnail pixels) of the max filter that removes ink from the estimate. */
    private const val DILATE_RADIUS = 2

    /** Radius (in thumbnail pixels) of the box blur that smooths the estimate. */
    private const val BLUR_RADIUS = 2

    /** Long edge of the coarse illumination field used to tell paper from objects. */
    private const val COARSE_SIZE = 32

    /** Max filter radius on the coarse field — erases anything smaller than a good part of the page. */
    private const val COARSE_DILATE_RADIUS = 3

    /** Once the light is divided out, paper is at least this bright (0..255). */
    private const val PAPER_MIN_NORMALIZED = 170

    /** Paper is close to neutral; anything more colourful is ink, a stamp or a photo (percent). */
    private const val PAPER_MAX_SATURATION_PERCENT = 30

    /** Upper bound for the diffusion that fills the non-paper regions of the estimate. */
    private const val FILL_ITERATIONS = 64

    /** Levels curve: on a fully whitened page, everything above this becomes white. */
    private const val WHITE_POINT = 236f

    /** `blackPoint` 1f crushes everything below this (0..255) to black. */
    private const val BLACK_POINT_MAX = 150f

    /** Saturation applied after whitening so coloured ink stays visible. */
    private const val COLOR_SATURATION_BOOST = 1.2f

    /** Threshold applied to the normalized luminance in black & white mode. */
    private const val BW_THRESHOLD = 145

    /**
     * Chroma (max channel - min channel, 0..255) below which a pixel counts as neutral.
     *
     * Dividing by a warm paper colour leaves a faint tint on black ink, and the saturation boost
     * then makes it visible — black text drifting towards red. Anything this close to grey is
     * forced back to grey; real coloured ink is far above the threshold and keeps its hue.
     */
    private const val NEUTRAL_CHROMA_LIMIT = 26

    /** Chroma at which a pixel counts as fully coloured ink and follows the gentle curve. */
    private const val COLOR_FULL_CHROMA = 90

    /**
     * Black point used for coloured ink. A high black point is what makes text properly black,
     * but applying it to a red stamp or a blue pen would turn them into dark mud, so colour keeps
     * its own gentle curve and the two are blended by how colourful the pixel is.
     */
    private const val COLOR_BLACK_POINT = 0.12f

    /**
     * Apply the user's scan settings to a bitmap.
     *
     * @param source input bitmap
     * @param adjustments whitening strength, black point and the black & white switch
     * @param recycleSource recycle [source] when a new bitmap is produced
     * @return the enhanced bitmap (may be [source] itself when nothing has to be done)
     */
    fun enhance(
        source: Bitmap,
        adjustments: ScanAdjustments,
        recycleSource: Boolean = true
    ): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source

        val untouched = !adjustments.blackAndWhite &&
            adjustments.whitenStrength <= 0f &&
            adjustments.blackPoint <= 0f
        if (untouched) return source

        return normalize(source, adjustments, recycleSource)
    }

    /**
     * Background-normalizing filter. See the class doc for the algorithm.
     */
    private fun normalize(
        source: Bitmap,
        adjustments: ScanAdjustments,
        recycleSource: Boolean
    ): Bitmap {
        val width = source.width
        val height = source.height
        val amount = adjustments.whitenStrength.coerceIn(0f, 1f)
        val binary = adjustments.blackAndWhite

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // With whitening off there is no paper to divide out, so the estimate is not computed.
        val bgScale = min(1f, BACKGROUND_SIZE.toFloat() / max(width, height))
        val bgWidth = max(2, (width * bgScale).roundToInt())
        val bgHeight = max(2, (height * bgScale).roundToInt())
        val background = if (amount > 0f) {
            estimateBackground(pixels, width, height, bgWidth, bgHeight)
        } else {
            null
        }

        // The white point follows the whitening: an untouched photo keeps its highlights.
        val whitePoint = 255f - (255f - WHITE_POINT) * amount
        val inkLevels = buildLevelsLut(adjustments.blackPoint, whitePoint)
        val colorLevels = buildLevelsLut(min(adjustments.blackPoint, COLOR_BLACK_POINT), whitePoint)

        // Mapping from full-resolution coordinates to the background estimate.
        val scaleX = if (width > 1) (bgWidth - 1).toFloat() / (width - 1) else 0f
        val scaleY = if (height > 1) (bgHeight - 1).toFloat() / (height - 1) else 0f

        val saturation = if (binary) 1f else 1f + (COLOR_SATURATION_BOOST - 1f) * amount

        for (y in 0 until height) {
            val srcY = y * scaleY
            val y0 = srcY.toInt().coerceIn(0, bgHeight - 1)
            val y1 = min(y0 + 1, bgHeight - 1)
            val wy = srcY - y0
            val rowOffset = y * width

            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                val a = pixel ushr 24
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 1. Divide by the local paper colour: paper -> white, ink stays dark.
                //    The strength slider mixes that back towards the photo as shot.
                var dr = r
                var dg = g
                var db = b
                if (background != null) {
                    val srcX = x * scaleX
                    val x0 = srcX.toInt().coerceIn(0, bgWidth - 1)
                    val x1 = min(x0 + 1, bgWidth - 1)
                    val wx = srcX - x0

                    val bg = bilinear(background, bgWidth, x0, x1, y0, y1, wx, wy)
                    dr = min(255, r * 255 / max((bg shr 16) and 0xFF, 1))
                    dg = min(255, g * 255 / max((bg shr 8) and 0xFF, 1))
                    db = min(255, b * 255 / max(bg and 0xFF, 1))

                    if (amount < 1f) {
                        dr = blend(r, dr, amount)
                        dg = blend(g, dg, amount)
                        db = blend(b, db, amount)
                    }
                }

                // 2. Levels on luminance, channels scaled by the same factor (hue preserved).
                //    Grey pixels get the user's black point, coloured ones a gentle curve.
                val luma = (dr * 77 + dg * 151 + db * 28) shr 8
                val chroma = max(dr, max(dg, db)) - min(dr, min(dg, db))
                val colorWeight = ((chroma - NEUTRAL_CHROMA_LIMIT).toFloat() /
                    (COLOR_FULL_CHROMA - NEUTRAL_CHROMA_LIMIT)).coerceIn(0f, 1f)
                val target = if (colorWeight <= 0f) {
                    inkLevels[luma]
                } else {
                    (inkLevels[luma] + (colorLevels[luma] - inkLevels[luma]) * colorWeight)
                        .roundToInt().coerceIn(0, 255)
                }

                var outR: Int
                var outG: Int
                var outB: Int
                if (binary) {
                    val value = if (target >= BW_THRESHOLD) 255 else 0
                    outR = value
                    outG = value
                    outB = value
                } else if (luma <= 0 || (colorWeight <= 0f && amount > 0f)) {
                    // Near-grey pixels (black text above all) must not inherit the paper's tint.
                    // Without whitening there is no tint to remove, so colours are left alone.
                    outR = target
                    outG = target
                    outB = target
                } else {
                    outR = min(255, dr * target / luma)
                    outG = min(255, dg * target / luma)
                    outB = min(255, db * target / luma)
                }

                // 3. Whitening lowers the saturation of coloured ink; give it back a little.
                //    Weighted by brightness so the boost never colours dark ink.
                if (saturation != 1f) {
                    val grey = outR * 0.213f + outG * 0.715f + outB * 0.072f
                    val boost = 1f + (saturation - 1f) * (grey / 255f)
                    outR = saturate(grey, outR, boost)
                    outG = saturate(grey, outG, boost)
                    outB = saturate(grey, outB, boost)
                }

                pixels[rowOffset + x] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        if (recycleSource && source != result && !source.isRecycled) {
            source.recycle()
        }
        return result
    }

    /**
     * Estimate the paper colour for every region of the image.
     *
     * Two passes, because a single blurred thumbnail cannot tell a shadow from a photo glued
     * onto the page — and treating a photo as background would bleach it:
     *
     *  1. A coarse, heavily dilated field describes the *light*: everything smaller than a good
     *     fraction of the page (text, stamps, photos) is erased from it.
     *  2. Dividing the thumbnail by that field makes paper bright everywhere, so one threshold
     *     (plus a saturation test) marks which thumbnail pixels are really paper. The rest is
     *     filled in by diffusing the surrounding paper colour inwards.
     *
     * @return the estimate as a [targetWidth] x [targetHeight] pixel array
     */
    private fun estimateBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val thumbnail = downsampleAverage(pixels, width, height, targetWidth, targetHeight)

        // Thin ink (text lines, hairlines) disappears here.
        val dilated = dilate(thumbnail, targetWidth, targetHeight, DILATE_RADIUS)

        // Pass 1 — coarse illumination field.
        val coarseScale = min(1f, COARSE_SIZE.toFloat() / max(targetWidth, targetHeight))
        val cw = max(2, (targetWidth * coarseScale).roundToInt())
        val ch = max(2, (targetHeight * coarseScale).roundToInt())
        val coarse = boxBlur(
            dilate(
                downsampleAverage(dilated, targetWidth, targetHeight, cw, ch),
                cw, ch, COARSE_DILATE_RADIUS
            ),
            cw, ch, BLUR_RADIUS
        )
        val illumination = resampleBilinear(coarse, cw, ch, targetWidth, targetHeight)

        // Pass 2 — which thumbnail pixels are paper?
        val isPaper = BooleanArray(targetWidth * targetHeight)
        var paperCount = 0
        for (i in dilated.indices) {
            val p = dilated[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val light = illumination[i]
            val nr = min(255, r * 255 / max((light shr 16) and 0xFF, 1))
            val ng = min(255, g * 255 / max((light shr 8) and 0xFF, 1))
            val nb = min(255, b * 255 / max(light and 0xFF, 1))
            val normalized = (nr * 77 + ng * 151 + nb * 28) shr 8

            val maxChannel = max(r, max(g, b))
            val minChannel = min(r, min(g, b))
            val saturation = if (maxChannel > 0) (maxChannel - minChannel) * 100 / maxChannel else 0

            val paper = normalized >= PAPER_MIN_NORMALIZED &&
                saturation <= PAPER_MAX_SATURATION_PERCENT
            isPaper[i] = paper
            if (paper) paperCount++
        }

        // Nothing looks like paper (a full-page photo, say) — keep the estimate as it is.
        if (paperCount == 0) isPaper.fill(true)

        val filled = fillFromPaper(dilated, isPaper, targetWidth, targetHeight)
        return boxBlur(filled, targetWidth, targetHeight, BLUR_RADIUS)
    }

    /**
     * Diffuse the paper colour into every region that is not paper, so text blocks, stamps and
     * photos take the colour of the paper around them instead of their own.
     */
    private fun fillFromPaper(
        pixels: IntArray,
        isPaper: BooleanArray,
        width: Int,
        height: Int
    ): IntArray {
        var current = pixels.copyOf()
        var known = isPaper.copyOf()
        var fallback = 0

        for (i in current.indices) {
            if (known[i] && current[i] > fallback) fallback = current[i]
        }

        for (pass in 0 until FILL_ITERATIONS) {
            var holes = 0
            val next = current.copyOf()
            val nextKnown = known.copyOf()

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (known[index]) continue

                    var r = 0; var g = 0; var b = 0; var count = 0
                    for (dy in -1..1) {
                        val sy = y + dy
                        if (sy < 0 || sy >= height) continue
                        for (dx in -1..1) {
                            val sx = x + dx
                            if (sx < 0 || sx >= width) continue
                            val neighbour = sy * width + sx
                            if (!known[neighbour]) continue
                            val p = current[neighbour]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            count++
                        }
                    }

                    if (count > 0) {
                        next[index] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                        nextKnown[index] = true
                    } else {
                        holes++
                    }
                }
            }

            current = next
            known = nextKnown
            if (holes == 0) break
        }

        // Anything the diffusion could not reach falls back to the brightest paper found.
        for (i in current.indices) {
            if (!known[i]) current[i] = fallback
        }
        return current
    }

    /** Area-average downsample. Alpha is dropped — the estimate only carries colour. */
    private fun downsampleAverage(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val result = IntArray(targetWidth * targetHeight)
        for (ty in 0 until targetHeight) {
            val y0 = ty * height / targetHeight
            val y1 = max(y0 + 1, (ty + 1) * height / targetHeight)
            for (tx in 0 until targetWidth) {
                val x0 = tx * width / targetWidth
                val x1 = max(x0 + 1, (tx + 1) * width / targetWidth)

                var r = 0L; var g = 0L; var b = 0L; var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val p = pixels[row + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                result[ty * targetWidth + tx] =
                    ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
            }
        }
        return result
    }

    /** Per-channel max filter — keeps the brightest (paper) value in the neighbourhood. */
    private fun dilate(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val p = pixels[row + sx]
                    r = max(r, (p shr 16) and 0xFF)
                    g = max(g, (p shr 8) and 0xFF)
                    b = max(b, p and 0xFF)
                }
                horizontal[row + x] = (r shl 16) or (g shl 8) or b
            }
        }

        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val p = horizontal[sy * width + x]
                    r = max(r, (p shr 16) and 0xFF)
                    g = max(g, (p shr 8) and 0xFF)
                    b = max(b, p and 0xFF)
                }
                result[y * width + x] = (r shl 16) or (g shl 8) or b
            }
        }
        return result
    }

    /** Separable box blur over the (already tiny) background estimate. */
    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val p = pixels[row + sx]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                horizontal[row + x] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }

        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val p = horizontal[sy * width + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                result[y * width + x] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        return result
    }

    /** Bilinear upsample of a small pixel array. */
    private fun resampleBilinear(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val result = IntArray(targetWidth * targetHeight)
        val scaleX = if (targetWidth > 1) (width - 1).toFloat() / (targetWidth - 1) else 0f
        val scaleY = if (targetHeight > 1) (height - 1).toFloat() / (targetHeight - 1) else 0f

        for (ty in 0 until targetHeight) {
            val srcY = ty * scaleY
            val y0 = srcY.toInt().coerceIn(0, height - 1)
            val y1 = min(y0 + 1, height - 1)
            val wy = srcY - y0
            for (tx in 0 until targetWidth) {
                val srcX = tx * scaleX
                val x0 = srcX.toInt().coerceIn(0, width - 1)
                val x1 = min(x0 + 1, width - 1)
                result[ty * targetWidth + tx] =
                    bilinear(pixels, width, x0, x1, y0, y1, srcX - x0, wy)
            }
        }
        return result
    }

    /** Bilinear sample of the background estimate, returned as 0xRRGGBB. */
    private fun bilinear(
        pixels: IntArray,
        width: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        wx: Float,
        wy: Float
    ): Int {
        val p00 = pixels[y0 * width + x0]
        val p01 = pixels[y0 * width + x1]
        val p10 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]

        var result = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val c00 = (p00 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c01 - c00) * wx
            val bottom = c10 + (c11 - c10) * wx
            val value = (top + (bottom - top) * wy).roundToInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }

    /**
     * Levels curve mapping `black..`[WHITE_POINT] onto 0..255, where `black` is the user's black
     * point. Raising it is what makes ink actually black instead of dark grey.
     */
    private fun buildLevelsLut(blackPoint: Float, whitePoint: Float): IntArray {
        val black = blackPoint.coerceIn(0f, 1f) * BLACK_POINT_MAX
        val lut = IntArray(256)
        val span = (whitePoint - black).coerceAtLeast(1f)
        for (i in 0..255) {
            lut[i] = (((i - black) / span) * 255f).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun blend(original: Int, processed: Int, amount: Float): Int =
        (original + (processed - original) * amount).roundToInt().coerceIn(0, 255)

    private fun saturate(grey: Float, channel: Int, saturation: Float): Int =
        (grey + (channel - grey) * saturation).roundToInt().coerceIn(0, 255)
}
