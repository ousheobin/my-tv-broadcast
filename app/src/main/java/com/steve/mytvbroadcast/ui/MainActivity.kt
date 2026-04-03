package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.SignalSource
import com.steve.mytvbroadcast.data.SignalSourceManager

class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var navigationDrawer: View
    private lateinit var sourcesList: RecyclerView
    private lateinit var btnSettings: LinearLayout

    private lateinit var drawerAdapter: DrawerSourceAdapter

    private var sources: List<SignalSource> = emptyList()

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
        setupNavigationDrawer()
    }

    private fun initViews() {
        navigationDrawer = findViewById(R.id.navigation_drawer)
        sourcesList = navigationDrawer.findViewById(R.id.sources_list)
        btnSettings = navigationDrawer.findViewById(R.id.btn_settings)
    }

    private fun setupNavigationDrawer() {
        drawerAdapter = DrawerSourceAdapter { source ->
            SignalSourceManager.setCurrentSourceId(source.id)
            browseFragment.reloadChannels()
            updateDrawerSelection()
        }

        sourcesList.layoutManager = LinearLayoutManager(this)
        sourcesList.adapter = drawerAdapter

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateDrawerSelection()
    }

    private fun updateDrawerSelection() {
        val currentSourceId = SignalSourceManager.getCurrentSourceId()
        drawerAdapter.setSelectedSource(currentSourceId)
    }

    private fun updateSources() {
        sources = SignalSourceManager.getSources()
        drawerAdapter.submitList(sources)
        updateDrawerSelection()
    }

    // SourceChangeListener implementation
    override fun onSourcesChanged() {
        runOnUiThread {
            updateSources()
            browseFragment.reloadChannels()
        }
    }

    override fun onResume() {
        super.onResume()
        SignalSourceManager.addListener(this)
        updateSources()
        browseFragment.reloadChannels()
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
    }
}
