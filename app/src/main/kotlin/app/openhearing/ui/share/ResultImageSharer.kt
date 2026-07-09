package app.openhearing.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import app.openhearing.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes the rendered results image to the app cache and hands it to the
 * system share sheet. The file lives only in `cacheDir/shared/` under a fixed
 * name (each export overwrites the last), and other apps can read it solely
 * through the one-time [FileProvider] grant on the share intent — the app
 * itself still has no INTERNET permission, so nothing leaves the phone unless
 * the user picks a target.
 */
object ResultImageSharer {
    suspend fun share(context: Context, bitmap: Bitmap) {
        val uri =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "results.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_chooser_title)),
        )
    }
}
