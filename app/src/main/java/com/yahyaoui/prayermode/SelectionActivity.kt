package com.yahyaoui.prayermode

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Context

class SelectionActivity : AppCompatActivity() {
    private lateinit var sharedHelper: SharedHelper

    override fun attachBaseContext(newBase: Context) {
        val savedLocale = SharedHelper(newBase).getSavedLocale()
        val locale = savedLocale ?: LocaleHelper.getPersistedLocale()
        if (BuildConfig.DEBUG) Log.d("SelectionActivity", "Attaching base context for locale: $locale")
        super.attachBaseContext(LocaleHelper.setLocale(newBase, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selection)
        LocaleHelper.setupLayoutDirection(this, window)
        sharedHelper = SharedHelper(this)

        val title = intent.getStringExtra("TITLE") ?: getString(R.string.select_option)
        val options = intent.getStringArrayExtra("OPTIONS") ?: emptyArray()
        val selectedIndexKey = intent.getStringExtra("SELECTED_INDEX_KEY") ?: "default_index_key"
        val defaultIndex = intent.getIntExtra("DEFAULT_INDEX", 0)

        findViewById<TextView>(R.id.selectionTitle).text = title

        val listView = findViewById<ListView>(R.id.selectionListView)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, options)

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        val savedIndex = sharedHelper.getIntValue(selectedIndexKey, defaultIndex)
        listView.setItemChecked(savedIndex, true)

        listView.setOnItemClickListener { _, _, position, _ ->
            sharedHelper.saveIntValue(selectedIndexKey, position)
            val resultIntent = Intent()
            resultIntent.putExtra("SELECTED_INDEX", position)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
        val backButton: View = findViewById(R.id.selectionBackButton)
        backButton.setOnClickListener { finish() }
        if (BuildConfig.DEBUG) Log.d("SelectionActivity", "SelectionActivity loaded with title: $title, options: ${options.toList()}")
    }
}