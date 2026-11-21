package org.jarvis.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** The photos a search found, big, one under the other; a tap opens one in the phone's gallery. */
class PhotosActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (28 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        list.addView(TextView(this).apply {
            text = "🖼 ${intent.getStringExtra("title").orEmpty()}"
            textSize = 22f; setTextColor(Color.WHITE)
        })
        val uris = intent.getStringArrayListExtra("uris").orEmpty().map { Uri.parse(it) }
        val width = resources.displayMetrics.widthPixels
        for (u in uris) {
            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
                }
            }
            Thread {
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(u, Size(width, width), null)
                    else contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                } catch (_: Exception) { null }
                runOnUiThread { bmp?.let { image.setImageBitmap(it) } }
            }.start()
            list.addView(image, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }
}
