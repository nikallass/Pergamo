package com.yourname.pdftoolkit.domain.operations

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the scanner filter.
 *
 * The fixture is a synthetic "photo of a page": paper that is bright on the left and in shadow on
 * the right, with a dark grey text block (a photo of laser print is never pure black) and a red
 * stamp on it. That is exactly the case the filter exists for — a plain copy of such a photo
 * keeps the grey shadow and the washed-out ink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScanEnhancerTest {

    private val size = 200

    // Sample points inside the fixture.
    private val brightPaper = 10 to 10
    private val shadowedPaper = 190 to 10
    private val textPixel = 50 to 30
    private val stampPixel = 150 to 150

    private fun createUnevenlyLitPage(): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Paper: bright on the left, in shadow on the right, slightly warm.
                val lightness = 235f - 85f * (x.toFloat() / size)
                var r = lightness
                var g = lightness * 0.96f
                var b = lightness * 0.88f

                val isText = x in 20..80 && y in 20..40
                val isStamp = x in 120..180 && y in 120..180
                when {
                    isText -> {
                        r *= 0.30f; g *= 0.30f; b *= 0.30f
                    }
                    isStamp -> {
                        g *= 0.25f; b *= 0.25f
                    }
                }

                bitmap.setPixel(x, y, Color.rgb(r.toInt(), g.toInt(), b.toInt()))
            }
        }
        return bitmap
    }

    private fun enhance(
        source: Bitmap,
        blackAndWhite: Boolean = false,
        strength: Float = 1f,
        blackPoint: Float = ScanEnhancer.DEFAULT_BLACK_POINT
    ): Bitmap = ScanEnhancer.enhance(
        source,
        ScanAdjustments(blackAndWhite, strength, blackPoint),
        recycleSource = false
    )

    private fun Bitmap.pixelAt(point: Pair<Int, Int>): Int = getPixel(point.first, point.second)

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 77 + Color.green(pixel) * 151 + Color.blue(pixel) * 28) shr 8

    @Test
    fun whiteningTurnsUnevenlyLitPaperWhite() {
        val result = enhance(createUnevenlyLitPage())

        for (point in listOf(brightPaper, shadowedPaper)) {
            val pixel = result.pixelAt(point)
            assertTrue(
                "Paper at $point should be white, was ${pixel.toHexString()}",
                Color.red(pixel) >= 245 && Color.green(pixel) >= 245 && Color.blue(pixel) >= 245
            )
        }
    }

    @Test
    fun defaultBlackPointMakesInkProperlyBlack() {
        val result = enhance(createUnevenlyLitPage())

        val text = result.pixelAt(textPixel)
        assertTrue(
            "Ink should be black, not dark grey, was ${text.toHexString()}",
            luminance(text) <= 20
        )
    }

    @Test
    fun blackPointControlsHowFarGreysAreCrushed() {
        val soft = enhance(createUnevenlyLitPage(), blackPoint = 0f).pixelAt(textPixel)
        val hard = enhance(createUnevenlyLitPage(), blackPoint = 1f).pixelAt(textPixel)

        assertTrue(
            "A zero black point should leave the ink grey, was ${soft.toHexString()}",
            luminance(soft) > 40
        )
        assertTrue(
            "Raising the black point must darken the ink: ${soft.toHexString()} -> ${hard.toHexString()}",
            luminance(hard) < luminance(soft)
        )
    }

    @Test
    fun inkStaysNeutralInsteadOfPickingUpThePaperTint() {
        for (blackPoint in listOf(0f, ScanEnhancer.DEFAULT_BLACK_POINT)) {
            val text = enhance(createUnevenlyLitPage(), blackPoint = blackPoint).pixelAt(textPixel)
            val r = Color.red(text)
            val g = Color.green(text)
            val b = Color.blue(text)
            assertTrue(
                "Black ink must not drift towards the paper colour, was ${text.toHexString()}",
                kotlin.math.abs(r - g) <= 2 && kotlin.math.abs(g - b) <= 2
            )
        }
    }

    @Test
    fun coloredInkStaysVividEvenAtAHighBlackPoint() {
        val source = createUnevenlyLitPage()
        val before = source.pixelAt(stampPixel)
        val after = enhance(source, blackPoint = 0.9f).pixelAt(stampPixel)

        assertTrue(
            "Red stamp should stay bright red, was ${after.toHexString()}",
            Color.red(after) > 200 &&
                Color.red(after) > Color.green(after) + 100 &&
                Color.red(after) > Color.blue(after) + 100
        )
        // Whitening must not wash the colour out: saturation should not drop.
        val saturationBefore = Color.red(before) - Color.green(before)
        val saturationAfter = Color.red(after) - Color.green(after)
        assertTrue(
            "Saturation dropped from $saturationBefore to $saturationAfter",
            saturationAfter >= saturationBefore
        )
    }

    @Test
    fun blackAndWhiteModeProducesTwoColorsOnly() {
        val result = enhance(createUnevenlyLitPage(), blackAndWhite = true)

        for (y in 0 until size step 7) {
            for (x in 0 until size step 7) {
                val pixel = result.getPixel(x, y)
                assertTrue(
                    "Unexpected color ${pixel.toHexString()} at $x,$y",
                    pixel == Color.BLACK || pixel == Color.WHITE
                )
            }
        }
        assertEquals(Color.WHITE, result.pixelAt(shadowedPaper))
        assertEquals(Color.BLACK, result.pixelAt(textPixel))
    }

    @Test
    fun blackPointActsAsContrastWhenWhiteningIsOff() {
        val source = createUnevenlyLitPage()
        val flat = enhance(source, strength = 0f, blackPoint = 0f)
        val contrasted = enhance(source, strength = 0f, blackPoint = 0.7f)

        assertTrue(
            "Without whitening the black point must still darken the ink: " +
                "${flat.pixelAt(textPixel).toHexString()} -> ${contrasted.pixelAt(textPixel).toHexString()}",
            luminance(contrasted.pixelAt(textPixel)) < luminance(flat.pixelAt(textPixel))
        )
        // ...while leaving the paper roughly where the photo had it — this is contrast, not a scan.
        assertTrue(
            "Paper should not be bleached without whitening, was " +
                contrasted.pixelAt(shadowedPaper).toHexString(),
            luminance(contrasted.pixelAt(shadowedPaper)) < 245
        )
    }

    @Test
    fun neutralSettingsReturnTheSourceUntouched() {
        val source = createUnevenlyLitPage()

        val result = ScanEnhancer.enhance(
            source,
            ScanAdjustments(blackAndWhite = false, whitenStrength = 0f, blackPoint = 0f)
        )

        assertSame(source, result)
    }

    private fun Int.toHexString(): String = String.format("#%08X", this)
}
