package com.yahyaoui.prayermode

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment

class MigrationDialog : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_migration, container, false)
        val btnGotIt: Button = view.findViewById(R.id.btnGotIt)
        btnGotIt.setOnClickListener {
            if (BuildConfig.DEBUG) Log.d("MigrationDialog", "Got it button clicked.")
            dismiss()
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        val displayMetrics = resources.displayMetrics
        val maxDialogHeight = (displayMetrics.heightPixels * 0.7).toInt()
        val contentWidth = (displayMetrics.widthPixels * 0.85).toInt()
        dialog?.window?.setLayout(contentWidth, maxDialogHeight)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                return@setOnKeyListener true
            }
            false
        }
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        return dialog
    }
}