package com.yahyaoui.prayermode

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
class PrayerTileService : TileService() {
    private val sharedHelper: SharedHelper by lazy { SharedHelper(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        handleTileClick()
    }
    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (sharedHelper.getSwitchState()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
        if (BuildConfig.DEBUG) Log.d("TileService", "Tile state updated: ${tile.state}")
    }
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun handleTileClick() {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("FROM_TILE", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(applicationContext, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}