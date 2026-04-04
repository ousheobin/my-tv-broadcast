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
import com.steve.mytvbroadcast.data.SignalSourceManager

class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener, CategoryCallback {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var navigationDrawer: View
    private lateinit var categoryList: RecyclerView
    private lateinit var btnSettings: LinearLayout

    private lateinit var categoryAdapter: CategoryAdapter

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
        browseFragment.categoryCallback = this
    }

    private fun initViews() {
        navigationDrawer = findViewById(R.id.navigation_drawer)
        categoryList = navigationDrawer.findViewById(R.id.category_list)
        btnSettings = navigationDrawer.findViewById(R.id.btn_settings)
    }

    private fun setupNavigationDrawer() {
        categoryAdapter = CategoryAdapter { index ->
            browseFragment.onCategorySelected(index)
            categoryAdapter.setSelectedIndex(index)
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // CategoryCallback implementation
    override fun onCategoriesLoaded(categories: List<String>) {
        categoryAdapter.submitList(categories)
    }

    // SourceChangeListener implementation
    override fun onSourcesChanged() {
        runOnUiThread {
            browseFragment.reloadChannels()
        }
    }

    override fun onResume() {
        super.onResume()
        SignalSourceManager.addListener(this)
        browseFragment.reloadChannels()
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
    }
}
