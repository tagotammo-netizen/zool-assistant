package org.jarvis.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A quick-settings tile: one tap from the notification shade starts or stops listening (for example when someone is asleep). */
class JarvisTile : TileService() {

    override fun onStartListening() { paint() }

    override fun onClick() {
        val service = JarvisService.instance
        if (service == null) {                                  // the accessibility service is off: open the app instead
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(open)
            }
            return
        }
        if (service.listening) service.stopListening(true) else service.startListening(true)
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val on = JarvisService.instance?.listening == true
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = Brand.NAME
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "يستمع" else "متوقف"
        tile.updateTile()
    }
}
