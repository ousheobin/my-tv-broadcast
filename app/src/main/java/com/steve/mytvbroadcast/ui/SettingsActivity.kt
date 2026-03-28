package com.steve.mytvbroadcast.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.SignalSourceManager
import com.steve.mytvbroadcast.server.SignalSourceHttpServer
import com.steve.mytvbroadcast.ui.settings.SettingsCategories
import com.steve.mytvbroadcast.ui.settings.SettingsCategory
import com.steve.mytvbroadcast.ui.settings.SettingsCategoryAdapter
import com.steve.mytvbroadcast.util.NetworkUtils
import com.steve.mytvbroadcast.util.QrCodeUtils

class SettingsActivity : FragmentActivity() {

    private lateinit var categoriesList: RecyclerView
    private lateinit var serverSettings: View
    private lateinit var sourceSettings: View

    // Server settings views
    private lateinit var serverToggleContainer: LinearLayout
    private lateinit var serverStatusText: TextView
    private lateinit var toggleButton: TextView
    private lateinit var errorMessage: TextView
    private lateinit var qrCodeContainer: LinearLayout
    private lateinit var qrCodeImage: ImageView
    private lateinit var qrCodeUrl: TextView

    // Source settings views
    private lateinit var sourcesList: RecyclerView
    private lateinit var inputSourceName: EditText
    private lateinit var inputSourceUrl: EditText
    private lateinit var btnAddSource: TextView

    private lateinit var categoryAdapter: SettingsCategoryAdapter
    private lateinit var sourceAdapter: SourceItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        setupCategories()
        setupServerSettings()
        setupSourceSettings()

        // Default to server
        showCategory(SettingsCategories.SERVER)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun initViews() {
        categoriesList = findViewById(R.id.categories_list)
        serverSettings = findViewById(R.id.server_settings)
        sourceSettings = findViewById(R.id.source_settings)

        serverToggleContainer = serverSettings.findViewById(R.id.server_toggle_container)
        serverStatusText = serverSettings.findViewById(R.id.server_status_text)
        toggleButton = serverSettings.findViewById(R.id.toggle_button)
        errorMessage = serverSettings.findViewById(R.id.error_message)
        qrCodeContainer = serverSettings.findViewById(R.id.qr_code_container)
        qrCodeImage = serverSettings.findViewById(R.id.qr_code_image)
        qrCodeUrl = serverSettings.findViewById(R.id.qr_code_url)

        sourcesList = sourceSettings.findViewById(R.id.sources_list)
        inputSourceName = sourceSettings.findViewById(R.id.input_source_name)
        inputSourceUrl = sourceSettings.findViewById(R.id.input_source_url)
        btnAddSource = sourceSettings.findViewById(R.id.btn_add_source)
    }

    private fun setupCategories() {
        categoryAdapter = SettingsCategoryAdapter(SettingsCategories.ALL) { category ->
            showCategory(category)
        }
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = categoryAdapter

        // 设置分类列表的焦点变化监听
        categoriesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCategoryFocus()
                }
            }
        })
    }

    private fun updateCategoryFocus() {
        val layoutManager = categoriesList.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            categoryAdapter.setFocusedPosition(firstVisible)
        }
    }

    private fun setupServerSettings() {
        updateServerStatus()
        updateQrCodeDisplay()

        // 使用 setOnKeyListener 处理 D-pad 按键，比 setOnClickListener 更可靠
        toggleButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) {
                toggleServer()
                true
            } else if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                toggleServer()
                true
            } else {
                false
            }
        }
    }

    private fun toggleServer() {
        val running = SignalSourceHttpServer.isServerRunning()
        if (running) {
            SignalSourceHttpServer.stop()
            Toast.makeText(this, "HTTP 服务已关闭", Toast.LENGTH_SHORT).show()
            errorMessage.visibility = View.GONE
        } else {
            val success = SignalSourceHttpServer.start(SignalSourceManager.getServerPort())
            if (success) {
                Toast.makeText(this, "HTTP 服务已启动", Toast.LENGTH_SHORT).show()
                errorMessage.visibility = View.GONE
            } else {
                val error = SignalSourceHttpServer.lastError ?: "未知错误"
                errorMessage.text = "启动失败: $error"
                errorMessage.visibility = View.VISIBLE
            }
        }
        updateServerStatus()
        updateQrCodeDisplay()
    }

    private fun updateServerStatus() {
        val running = SignalSourceHttpServer.isServerRunning()
        serverStatusText.text = if (running) "已开启" else "已关闭"
        serverStatusText.setTextColor(
            if (running) Color.parseColor("#4CAF50") else Color.parseColor("#888888")
        )
        if (running) {
            errorMessage.visibility = View.GONE
        }
    }

    private fun updateQrCodeDisplay() {
        if (SignalSourceHttpServer.isServerRunning()) {
            val port = SignalSourceManager.getServerPort()
            val ip = NetworkUtils.getDeviceIpAddress(this)
            if (ip != null) {
                val url = "http://$ip:$port"
                qrCodeUrl.text = url
                // 在后台线程生成二维码，避免阻塞主线程
                Thread {
                    val qrBitmap = QrCodeUtils.generateQrCodeBitmap(url, 400)
                    runOnUiThread {
                        if (qrBitmap != null) {
                            qrCodeImage.setImageBitmap(qrBitmap)
                            qrCodeContainer.visibility = View.VISIBLE
                        } else {
                            qrCodeContainer.visibility = View.GONE
                        }
                    }
                }.start()
            } else {
                qrCodeContainer.visibility = View.GONE
            }
        } else {
            qrCodeContainer.visibility = View.GONE
        }
    }

    private fun setupSourceSettings() {
        sourceAdapter = SourceItemAdapter(
            SignalSourceManager.getSources(),
            onToggleClicked = { source ->
                SignalSourceManager.updateSource(source.copy(enabled = !source.enabled))
                refreshSources()
            },
            onDeleteClicked = { source ->
                SignalSourceManager.removeSource(source.id)
                refreshSources()
                Toast.makeText(this, "已删除: ${source.name}", Toast.LENGTH_SHORT).show()
            }
        )
        sourcesList.layoutManager = LinearLayoutManager(this)
        sourcesList.adapter = sourceAdapter

        // 添加信号源
        btnAddSource.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.action == KeyEvent.ACTION_UP) {
                addSource()
                true
            } else {
                false
            }
        }
        btnAddSource.setOnClickListener { addSource() }
    }

    private fun addSource() {
        val name = inputSourceName.text.toString().trim()
        val url = inputSourceUrl.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "请输入信号源名称", Toast.LENGTH_SHORT).show()
            inputSourceName.requestFocus()
            return
        }
        if (url.isBlank()) {
            Toast.makeText(this, "请输入 M3U URL", Toast.LENGTH_SHORT).show()
            inputSourceUrl.requestFocus()
            return
        }

        SignalSourceManager.addSource(name, url)
        inputSourceName.text.clear()
        inputSourceUrl.text.clear()
        inputSourceName.requestFocus()
        refreshSources()
        Toast.makeText(this, "已添加: $name", Toast.LENGTH_SHORT).show()
    }

    private fun showCategory(category: SettingsCategory) {
        serverSettings.visibility = View.GONE
        sourceSettings.visibility = View.GONE

        when (category.id) {
            "server" -> {
                serverSettings.visibility = View.VISIBLE
                // 服务器设置显示时，默认让 toggle_button 获取焦点
                toggleButton.requestFocus()
            }
            "sources" -> {
                sourceSettings.visibility = View.VISIBLE
                // 信号源设置显示时，让列表第一个item获取焦点
                sourcesList.post {
                    val viewHolder = sourcesList.findViewHolderForAdapterPosition(0)
                    viewHolder?.itemView?.requestFocus()
                }
            }
        }

        val position = SettingsCategories.ALL.indexOf(category)
        categoryAdapter.selectCategory(position)
    }

    private fun refreshSources() {
        sourceAdapter.updateSources(SignalSourceManager.getSources())
    }

    override fun onResume() {
        super.onResume()
        refreshSources()
        // 恢复时让分类列表第一个item获取焦点
        categoriesList.post {
            val viewHolder = categoriesList.findViewHolderForAdapterPosition(0)
            viewHolder?.itemView?.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                // 菜单键切换分类
                val currentIndex = SettingsCategories.ALL.indexOfFirst { cat ->
                    when (cat.id) {
                        "server" -> serverSettings.visibility == View.VISIBLE
                        "sources" -> sourceSettings.visibility == View.VISIBLE
                        else -> false
                    }
                }
                val nextIndex = (currentIndex + 1) % SettingsCategories.ALL.size
                showCategory(SettingsCategories.ALL[nextIndex])
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
