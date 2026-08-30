package com.yourname.pdftoolkit.domain.operations

/**
 * Everything the user can tune about how a page is turned into a scan.
 *
 * Two sliders cover the whole range instead of a list of preset modes: with no whitening and no
 * black point the page stays the photo it was, and turning them up walks it towards a clean
 * scan. Held per page (the pages of one document are often lit differently), with the screens
 * offering "apply to all pages" for the common case.
 */
data class ScanAdjustments(
    /** Two-tone output: smallest file, best for plain text. */
    val blackAndWhite: Boolean = false,
    /** How strongly the paper background is whitened (0f = photo as shot, 1f = white paper). */
    val whitenStrength: Float = ScanEnhancer.DEFAULT_WHITEN_STRENGTH,
    /**
     * Where black begins. On a whitened page this is what makes ink properly black instead of
     * dark grey; on an untouched photo it works as plain contrast.
     */
    val blackPoint: Float = ScanEnhancer.DEFAULT_BLACK_POINT
)
