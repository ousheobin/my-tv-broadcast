package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.SignalSourceManager

class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var sourceButtonsRecycler: RecyclerView
    private lateinit var btnSettings: ImageButton
    private lateinit var header: LinearLayout
    private lateinit var currentSourceName: TextView

    private lateinit var sourceAdapter: SourceButtonAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SignalSourceManager.init(this)
        ChannelDatabase.init(this)

        if (savedInstanceState == null) {
            browseFragment = BrowseFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, browseFragment)
                .commit()
        } else {
            browseFragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as BrowseFragment
        }

        initViews()
        setupSettingsButton()
        setupSourceButtons()
    }

    private fun initViews() {
        sourceButtonsRecycler = findViewById(R.id.source_buttons)
        btnSettings = findViewById(R.id.btn_settings)
        header = findViewById(R.id.header)
        currentSourceName = findViewById(R.id.current_source_name)
    }

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupSourceButtons() {
        sourceAdapter = SourceButtonAdapter { source ->
            SignalSourceManager.setCurrentSourceId(source.id)
            browseFragment.reloadChannels()
            updateSourceBar()
            updateSourceButtons()
        }

        sourceButtonsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sourceButtonsRecycler.adapter = sourceAdapter
    }

    private fun updateSourceBar() {
        val currentSource = SignalSourceManager.getSources().find { it.id == SignalSourceManager.getCurrentSourceId() }
        currentSourceName.text = currentSource?.name ?: "未选择"
    }

    private fun updateSourceButtons() {
        val sources = SignalSourceManager.getSources()
        val currentId = SignalSourceManager.getCurrentSourceId()
        sourceAdapter.setSources(sources, currentId)
        updateSourceBar()
    }

    // SourceChangeListener 实现
    override fun onSourcesChanged() {
        runOnUiThread {
            updateSourceButtons()
            browseFragment.reloadChannels()
        }
    }

    override fun onResume() {
        super.onResume()
        SignalSourceManager.addListener(this)
        updateSourceButtons()
        browseFragment.reloadChannels()
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
    }
}
