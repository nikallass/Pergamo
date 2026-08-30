package com.yourname.pdftoolkit.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.yourname.pdftoolkit.R

/**
 * Helper class to handle Reviews.
 * Open Source implementation shows a custom dialog linking to the fork on GitHub.
 */
object ReviewHelper {

    private const val TAG = "ReviewHelper"
    private const val FORK_URL = "https://github.com/nikallass/Pdf_Tools"
    private const val FORK_RELEASES_URL = "https://github.com/nikallass/Pdf_Tools/releases"

    /**
     * Trigger the review flow (Custom Dialog for Open Source).
     *
     * @param activity The activity context.
     */
    fun showReview(activity: Activity) {
        try {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(activity.getString(R.string.review_fork_title))
            builder.setMessage(activity.getString(R.string.review_fork_message))

            builder.setPositiveButton(activity.getString(R.string.review_star_fork)) { dialog, _ ->
                openUrl(activity, FORK_URL)
                dialog.dismiss()
            }

            builder.setNegativeButton(activity.getString(R.string.review_maybe_later)) { dialog, _ ->
                dialog.dismiss()
            }

            builder.setNeutralButton(activity.getString(R.string.review_view_releases)) { dialog, _ ->
                openUrl(activity, FORK_RELEASES_URL)
                dialog.dismiss()
            }

            builder.show()
            Log.d(TAG, "Custom review dialog shown")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing review dialog", e)
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL", e)
        }
    }
}
