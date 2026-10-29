package com.steve.mytvbroadcast.server

import android.util.Log
import com.steve.mytvbroadcast.data.EpgManager
import com.steve.mytvbroadcast.data.SignalSource
import com.steve.mytvbroadcast.data.SignalSourceManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket

class SignalSourceHttpServer : NanoHTTPD {

    constructor(port: Int) : super(port)

    constructor(hostname: String, port: Int) : super(hostname, port)

    companion object {
        private const val TAG = "HttpServer"
        var instance: SignalSourceHttpServer? = null
            private set
        var lastError: String? = null
            private set

        fun isPortAvailable(port: Int): Boolean {
            return try {
                ServerSocket(port).use { it.close(); true }
            } catch (e:
                                            Exception) {
                false
            }
        }

        fun start(port: Int = SignalSourceManager.getServerPort()): Boolean {
            if (instance != null) {
                Log.d(TAG, "Server already running")
                return true
            }
            lastError = null

            // 检查端口是否可用，如果不可用等待一下再试
            if (!isPortAvailable(port)) {
                Log.d(TAG, "Port $port in use, waiting...")
                Thread.sleep(500)
            }

            // 再检查一次，还不可用就尝试其他端口
            var actualPort = port
            if (!isPortAvailable(actualPort)) {
                actualPort = findAvailablePort(port)
                if (actualPort == -1) {
                    lastError = "无法找到可用端口"
                    Log.e(TAG, lastError ?: "error")
                    return false
                }
                Log.d(TAG, "Using alternate port $actualPort")
            }

            return try {
                instance = SignalSourceHttpServer("0.0.0.0", actualPort)
                instance?.start()
                SignalSourceManager.setServerEnabled(true)
                SignalSourceManager.setServerPort(actualPort)
                Log.d(TAG, "Server started on 0.0.0.0:$actualPort")
                true
            } catch (e: Exception) {
                val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Failed to start server: $errorMsg")
                lastError = errorMsg
                instance = null
                false
            }
        }

        private fun findAvailablePort(startPort: Int): Int {
            for (port in startPort until startPort + 100) {
                if (isPortAvailable(port)) {
                    return port
                }
            }
            return -1
        }

        fun stop() {
            try {
                instance?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server: ${e.message}")
            }
            instance = null
            SignalSourceManager.setServerEnabled(false)
            Log.d(TAG, "Server stopped")
        }

        fun isServerRunning(): Boolean = instance != null && instance?.isAlive == true
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        Log.d(TAG, "Request: $path")

        return when {
            path == "/" || path == "/index.html" -> serveHtml()
            path == "/api/sources" && session.method == Method.GET -> serveSources()
            path == "/api/sources" && session.method == Method.POST -> handleAddSource(session)
            path.startsWith("/api/sources/") && session.method == Method.PUT -> handleUpdateSource(session, path)
            path.startsWith("/api/sources/") && session.method == Method.DELETE -> handleDeleteSource(session, path)
            path == "/api/epg/sources" && session.method == Method.GET -> serveEpgSources()
            path == "/api/epg/sources" && session.method == Method.POST -> handleAddEpgSource(session)
            path.startsWith("/api/epg/sources/") && session.method == Method.PUT -> handleUpdateEpgSource(session, path)
            path.startsWith("/api/epg/sources/") && session.method == Method.DELETE -> handleDeleteEpgSource(path)
            path == "/api/epg/status" && session.method == Method.GET -> serveEpgStatus()
            path == "/api/epg/status" && session.method == Method.POST -> handleSetEpgStatus(session)
            path == "/api/epg/refresh" && session.method == Method.POST -> handleEpgRefresh()
            path == "/api/server/status" -> serveServerStatus()
            path == "/api/server/stop" -> handleStopServer()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveHtml(): Response {
        val html = buildBootstrapPage()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveSources(): Response {
        val sources = SignalSourceManager.getSources()
        val json = JSONArray()
        sources.forEach { source ->
            json.put(JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("url", source.url)
                put("enabled", source.enabled)
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleAddSource(session: IHTTPSession): Response {
        return try {
            // NanoHTTPD 需要先调用 parseBody 来解析 POST 参数
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val params = session.parameters
            val name = params["name"]?.firstOrNull() ?: return badRequest("Missing name")
            val url = params["url"]?.firstOrNull() ?: return badRequest("Missing url")

            if (name.isBlank() || url.isBlank()) {
                return badRequest("Name and URL are required")
            }

            SignalSourceManager.addSource(name, url)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun handleUpdateSource(session: IHTTPSession, path: String): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val id = path.removePrefix("/api/sources/")
            val params = session.parameters
            val name = params["name"]?.firstOrNull() ?: return badRequest("Missing name")
            val url = params["url"]?.firstOrNull() ?: return badRequest("Missing url")
            val enabled = params["enabled"]?.firstOrNull()?.toBoolean() ?: true

            val source = SignalSource(id, name, url, enabled)
            SignalSourceManager.updateSource(source)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun handleDeleteSource(session: IHTTPSession, path: String): Response {
        return try {
            val id = path.removePrefix("/api/sources/")
            SignalSourceManager.removeSource(id)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    // ==================== EPG 节目表接口 ====================

    private fun serveEpgSources(): Response {
        val json = JSONArray()
        EpgManager.getSources().forEach { source ->
            json.put(JSONObject().apply {
                put("id", source.id)
                put("url", source.url)
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleAddEpgSource(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val url = session.parameters["url"]?.firstOrNull() ?: return badRequest("Missing url")
            if (url.isBlank()) {
                return badRequest("URL is required")
            }
            EpgManager.addSource(url)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun handleUpdateEpgSource(session: IHTTPSession, path: String): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val id = path.removePrefix("/api/epg/sources/")
            val url = session.parameters["url"]?.firstOrNull() ?: return badRequest("Missing url")
            if (url.isBlank()) {
                return badRequest("URL is required")
            }
            EpgManager.updateSource(id, url)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun handleDeleteEpgSource(path: String): Response {
        return try {
            val id = path.removePrefix("/api/epg/sources/")
            EpgManager.removeSource(id)
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun serveEpgStatus(): Response {
        val json = JSONObject().apply {
            put("enabled", EpgManager.isEpgEnabled())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleSetEpgStatus(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val enabled = session.parameters["enabled"]?.firstOrNull()?.toBoolean() ?: true
            EpgManager.setEpgEnabled(enabled)
            okResponse("{\"success\": true, \"enabled\": " + enabled + "}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun handleEpgRefresh(): Response {
        return try {
            EpgManager.refreshAsync()
            okResponse("{\"success\": true}")
        } catch (e: Exception) {
            badRequest(e.message ?: "Error")
        }
    }

    private fun serveServerStatus(): Response {
        val json = JSONObject().apply {
            put("running", isServerRunning())
            put("port", SignalSourceManager.getServerPort())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleStopServer(): Response {
        Thread {
            stop()
        }.start()
        return okResponse("{\"success\": true}")
    }

    private fun badRequest(message: String): Response {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"$message\"}")
    }

    private fun okResponse(body: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun buildBootstrapPage(): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#ffffff">
<title>My TV Broadcast</title>
<style>
:root {
    --blue-0: #e6f4ff;
    --blue-1: #bae0ff;
    --blue-5: #1677ff;
    --blue-6: #0958d9;
    --gray-0: #ffffff;
    --gray-1: #fafafa;
    --gray-2: #f5f5f5;
    --gray-3: #f0f0f0;
    --gray-4: #d9d9d9;
    --gray-5: #bfbfbf;
    --gray-6: #8c8c8c;
    --gray-7: #595959;
    --gray-8: #434343;
    --gray-9: #262626;
    --danger: #ff4d4f;
}
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html { overscroll-behavior-y: contain; }
body {
    background: var(--gray-2);
    color: var(--gray-9);
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif;
    margin: 0;
    padding-bottom: calc(24px + env(safe-area-inset-bottom));
}
.topbar {
    position: sticky; top: 0; z-index: 10;
    background: var(--gray-0);
    border-bottom: 1px solid var(--gray-3);
    padding: 0 16px;
    padding-top: env(safe-area-inset-top);
}
.topbar-row {
    display: flex; align-items: center; justify-content: space-between;
    padding: 12px 0 4px;
}
.brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
.brand-title { font-size: 16px; font-weight: 600; line-height: 1.2; }
.icon-btn {
    border: 1px solid var(--gray-4); background: var(--gray-0); color: var(--gray-7);
    width: 40px; height: 40px; border-radius: 10px; font-size: 18px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.icon-btn:active { background: var(--gray-2); }
.tabs { display: flex; gap: 24px; }
.tab {
    border: none; background: none; padding: 10px 2px 12px;
    font-size: 15px; color: var(--gray-6); cursor: pointer;
    position: relative; font-family: inherit;
}
.tab.active { color: var(--blue-5); font-weight: 600; }
.tab.active::after {
    content: ''; position: absolute; left: 25%; right: 25%; bottom: 0;
    height: 3px; border-radius: 2px; background: var(--blue-5);
}
.page { max-width: 760px; margin: 0 auto; padding: 16px; }
.view.hidden { display: none; }
.card {
    background: var(--gray-0); border: 1px solid var(--gray-3);
    border-radius: 12px; padding: 16px; margin-bottom: 16px;
}
.card-title { font-size: 14px; font-weight: 600; color: var(--gray-8); margin: 0 0 12px; }
.field { margin-bottom: 12px; }
.field label { display: block; font-size: 13px; color: var(--gray-7); margin-bottom: 6px; }
.form-control {
    display: block; width: 100%;
    border: 1px solid var(--gray-4); border-radius: 8px;
    padding: 10px 12px; font-size: 16px; line-height: 1.4;
    background: var(--gray-0); color: var(--gray-9); font-family: inherit;
}
.form-control:focus { border-color: var(--blue-5); box-shadow: 0 0 0 2px var(--blue-1); outline: none; }
.btn {
    border: none; border-radius: 8px; background: var(--gray-3); color: var(--gray-8);
    padding: 10px 16px; font-size: 14px; min-height: 44px; cursor: pointer;
    font-family: inherit;
}
.btn:active { opacity: .75; }
.btn.primary { background: var(--blue-5); color: var(--gray-0); }
.btn.primary:hover { background: var(--blue-6); }
.btn.ghost { background: var(--gray-0); border: 1px solid var(--gray-4); color: var(--gray-7); }
.btn.danger { background: var(--gray-0); border: 1px solid var(--gray-4); color: var(--gray-6); }
.btn.danger:hover { border-color: var(--danger); color: var(--danger); background: #fff1f0; }
.btn.block { display: block; width: 100%; }
.btn.sm { min-height: 36px; padding: 6px 12px; font-size: 13px; border-radius: 8px; }
.btnrow { display: flex; gap: 10px; }
.btnrow .btn { flex: 1; }
.list-head {
    display: flex; align-items: center; justify-content: space-between;
    font-size: 13px; color: var(--gray-6); margin: 4px 2px 10px;
}
.item {
    background: var(--gray-0); border: 1px solid var(--gray-3);
    border-radius: 12px; padding: 14px 16px; margin-bottom: 10px;
}
.item.disabled-item { opacity: .6; }
.item-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.item-name { font-size: 15px; font-weight: 600; color: var(--gray-9); word-break: break-all; }
.badge {
    display: inline-block; padding: 2px 8px; border-radius: 999px;
    font-size: 12px; flex: 0 0 auto;
}
.badge-on { background: var(--blue-0); color: var(--blue-6); }
.badge-off { background: var(--gray-3); color: var(--gray-6); }
.item-url {
    font-size: 12px; color: var(--gray-6); margin: 6px 0 10px;
    word-break: break-all; line-height: 1.5;
}
.item-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.empty {
    text-align: center; color: var(--gray-6); font-size: 14px;
    padding: 32px 16px; border: 1px dashed var(--gray-4); border-radius: 12px;
}
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.switch-row .row1 { font-size: 15px; font-weight: 600; color: var(--gray-9); }
.switch-row .hint { font-size: 12px; color: var(--gray-6); margin-top: 2px; }
.switch { position: relative; display: inline-block; width: 50px; height: 30px; flex: 0 0 auto; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider {
    position: absolute; inset: 0; border-radius: 999px; cursor: pointer;
    background: var(--gray-4); transition: background .2s;
}
.slider::before {
    content: ''; position: absolute; left: 3px; top: 3px;
    width: 24px; height: 24px; border-radius: 50%;
    background: var(--gray-0); transition: transform .2s;
    box-shadow: 0 1px 3px rgba(0,0,0,.25);
}
.switch input:checked + .slider { background: var(--blue-5); }
.switch input:checked + .slider::before { transform: translateX(20px); }
.toast {
    position: fixed; left: 50%; bottom: calc(28px + env(safe-area-inset-bottom));
    transform: translateX(-50%) translateY(8px);
    background: rgba(0,0,0,.82); color: var(--gray-0);
    padding: 10px 18px; border-radius: 8px; font-size: 14px;
    max-width: 82vw; text-align: center; line-height: 1.4;
    opacity: 0; pointer-events: none; transition: opacity .2s, transform .2s; z-index: 50;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
.toast.error { background: var(--danger); }
.sheet-mask {
    position: fixed; inset: 0; background: rgba(0,0,0,.45);
    display: none; align-items: flex-end; justify-content: center; z-index: 40;
}
.sheet-mask.open { display: flex; }
.sheet {
    background: var(--gray-0); width: 100%; max-width: 560px;
    border-radius: 16px 16px 0 0;
    padding: 20px 16px calc(20px + env(safe-area-inset-bottom));
    animation: slide-up .22s ease-out;
}
@keyframes slide-up { from { transform: translateY(60%); opacity: .4; } to { transform: none; opacity: 1; } }
.sheet-title { font-size: 16px; font-weight: 600; margin-bottom: 14px; }
.sheet-actions { display: flex; gap: 10px; margin-top: 4px; }
.sheet-actions .btn { flex: 1; }
.modal-mask {
    position: fixed; inset: 0; background: rgba(0,0,0,.45);
    display: none; align-items: center; justify-content: center; z-index: 45;
    padding: 24px;
}
.modal-mask.open { display: flex; }
.modal {
    background: var(--gray-0); border-radius: 16px;
    width: 100%; max-width: 340px; padding: 20px;
    animation: pop .18s ease-out;
}
@keyframes pop { from { transform: scale(.92); opacity: .4; } to { transform: none; opacity: 1; } }
.modal-title { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.modal-body { font-size: 14px; color: var(--gray-7); line-height: 1.5; word-break: break-all; margin-bottom: 18px; }
.btn.solid-danger { background: var(--danger); color: var(--gray-0); }
.btn.solid-danger:hover { background: #d9363e; }
@media (min-width: 720px) {
    .page { padding: 24px 16px; }
    .add-grid { display: flex; gap: 12px; align-items: flex-end; }
    .add-grid .field { flex: 1; margin-bottom: 0; }
    .add-grid .field.wide { flex: 2; }
    .sheet-mask { align-items: center; }
    .sheet { border-radius: 16px; padding-bottom: 20px; }
}
</style>
</head>
<body>
<header class="topbar">
    <div class="topbar-row">
        <div class="brand">
            <div class="brand-title">My TV Broadcast</div>
        </div>
        <button class="icon-btn" id="refreshBtn" title="刷新">&#x21bb;</button>
    </div>
    <nav class="tabs">
        <button class="tab active" data-tab="sources" id="tabSources">频道源</button>
        <button class="tab" data-tab="epg" id="tabEpg">节目表</button>
    </nav>
</header>

<main class="page">
    <section class="view" id="view-sources">
        <form class="card" id="addSourceForm">
            <div class="card-title">添加频道源</div>
            <div class="add-grid">
                <div class="field">
                    <label for="sourceName">名称</label>
                    <input class="form-control" type="text" id="sourceName" placeholder="例如：主源" autocomplete="off" required>
                </div>
                <div class="field wide">
                    <label for="sourceUrl">M3U URL</label>
                    <input class="form-control" type="url" inputmode="url" id="sourceUrl" placeholder="https://…/list.m3u" autocomplete="off" required>
                </div>
                <div class="field" style="flex:0 0 auto">
                    <button type="submit" class="btn primary">添加</button>
                </div>
            </div>
        </form>
        <div class="list-head"><span id="sourceCount">加载中…</span></div>
        <div id="sourceList"><div class="empty">加载中…</div></div>
    </section>

    <section class="view hidden" id="view-epg">
        <div class="card">
            <div class="switch-row">
                <div>
                    <div class="row1">节目表（EPG）总开关</div>
                    <div class="hint">关闭后播放页不再显示节目信息</div>
                </div>
                <label class="switch">
                    <input type="checkbox" id="epgEnabled">
                    <span class="slider"></span>
                </label>
            </div>
        </div>
        <form class="card" id="addEpgForm">
            <div class="card-title">添加节目表源</div>
            <div class="field">
                <label for="epgUrl">XMLTV URL</label>
                <input class="form-control" type="url" inputmode="url" id="epgUrl" placeholder="https://…/epg.xml.gz" autocomplete="off" required>
            </div>
            <div class="btnrow">
                <button type="submit" class="btn primary">添加源</button>
                <button type="button" class="btn ghost" id="epgRefreshBtn">拉取最新节目表</button>
            </div>
        </form>
        <div class="list-head"><span id="epgCount">加载中…</span><span>按顺序检索，排前的源优先</span></div>
        <div id="epgList"><div class="empty">加载中…</div></div>
    </section>
</main>

<div class="toast" id="toast"></div>

<div class="modal-mask" id="confirmMask">
    <div class="modal">
        <div class="modal-title" id="confirmTitle">确认操作</div>
        <div class="modal-body" id="confirmBody"></div>
        <div class="sheet-actions">
            <button class="btn ghost" id="confirmCancel">取消</button>
            <button class="btn solid-danger" id="confirmOk">删除</button>
        </div>
    </div>
</div>

<div class="sheet-mask" id="sheetMask">
    <div class="sheet">
        <div class="sheet-title" id="sheetTitle">编辑</div>
        <div class="field" id="sheetNameField">
            <label for="sheetName">名称</label>
            <input class="form-control" type="text" id="sheetName" autocomplete="off">
        </div>
        <div class="field">
            <label for="sheetUrl">URL</label>
            <input class="form-control" type="url" inputmode="url" id="sheetUrl" autocomplete="off">
        </div>
        <div class="sheet-actions">
            <button class="btn ghost" id="sheetCancel">取消</button>
            <button class="btn primary" id="sheetOk">保存</button>
        </div>
    </div>
</div>

<script>
var state = { sources: [], epg: [], epgEnabled: true, sheet: null };

function byId(id) { return document.getElementById(id); }

function esc(text) {
    var div = document.createElement('div');
    div.textContent = (text === null || text === undefined) ? '' : String(text);
    return div.innerHTML;
}

function toast(msg, isError) {
    var el = byId('toast');
    el.textContent = msg;
    el.className = 'toast show' + (isError ? ' error' : '');
    clearTimeout(el._timer);
    el._timer = setTimeout(function () { el.className = 'toast'; }, 2400);
}

async function api(url, options) {
    var res = await fetch(url, options);
    var text = await res.text();
    var data = null;
    try { data = JSON.parse(text); } catch (e) { /* 非 JSON 响应 */ }
    if (!res.ok || (data && data.error)) {
        throw new Error((data && data.error) || ('HTTP ' + res.status));
    }
    return data;
}

function formParams(obj) {
    var p = new URLSearchParams();
    Object.keys(obj).forEach(function (k) { p.append(k, String(obj[k])); });
    return p;
}

function formBody(obj) {
    return {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formParams(obj)
    };
}

// ==================== 频道源 ====================

async function loadSources() {
    try {
        state.sources = await api('/api/sources');
        renderSources();
    } catch (e) {
        byId('sourceList').innerHTML = '<div class="empty">加载失败: ' + esc(e.message) + '</div>';
    }
}

function renderSources() {
    var list = byId('sourceList');
    byId('sourceCount').textContent = '共 ' + state.sources.length + ' 个频道源';
    if (state.sources.length === 0) {
        list.innerHTML = '<div class="empty">暂无频道源，先在上方添加</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < state.sources.length; i++) {
        var s = state.sources[i];
        html += '<div class="item' + (s.enabled ? '' : ' disabled-item') + '">'
            + '<div class="item-head"><div class="item-name">' + esc(s.name) + '</div>'
            + '<span class="badge ' + (s.enabled ? 'badge-on' : 'badge-off') + '">' + (s.enabled ? '启用' : '禁用') + '</span></div>'
            + '<div class="item-url">' + esc(s.url) + '</div>'
            + '<div class="item-actions">'
            + '<button class="btn sm ghost" data-act="toggle" data-id="' + esc(s.id) + '">' + (s.enabled ? '禁用' : '启用') + '</button>'
            + '<button class="btn sm ghost" data-act="edit" data-id="' + esc(s.id) + '">编辑</button>'
            + '<button class="btn sm ghost" data-act="copy" data-id="' + esc(s.id) + '">复制URL</button>'
            + '<button class="btn sm danger" data-act="del" data-id="' + esc(s.id) + '">删除</button>'
            + '</div></div>';
    }
    list.innerHTML = html;
}

async function toggleSource(id) {
    var s = findSource(id);
    if (!s) return;
    try {
        await api('/api/sources/' + id, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: formParams({ name: s.name, url: s.url, enabled: !s.enabled })
        });
        toast((s.enabled ? '已禁用: ' : '已启用: ') + s.name);
        loadSources();
    } catch (e) {
        toast('操作失败: ' + e.message, true);
    }
}

async function deleteSource(id) {
    var s = findSource(id);
    if (!s) return;
    showConfirm('删除频道源', '确定删除「' + s.name + '」？删除后不可恢复。', function () {
        doDeleteSource(id, s.name);
    });
}

async function doDeleteSource(id, name) {
    try {
        await api('/api/sources/' + id, { method: 'DELETE' });
        toast('已删除: ' + name);
        loadSources();
    } catch (e) {
        toast('删除失败: ' + e.message, true);
    }
}

function findSource(id) {
    for (var i = 0; i < state.sources.length; i++) {
        if (state.sources[i].id === id) return state.sources[i];
    }
    return null;
}

// ==================== 节目表源 ====================

async function loadEpg() {
    try {
        var results = await Promise.all([api('/api/epg/sources'), api('/api/epg/status')]);
        state.epg = results[0];
        state.epgEnabled = results[1].enabled;
        byId('epgEnabled').checked = state.epgEnabled;
        renderEpg();
    } catch (e) {
        byId('epgList').innerHTML = '<div class="empty">加载失败: ' + esc(e.message) + '</div>';
    }
}

function renderEpg() {
    var list = byId('epgList');
    byId('epgCount').textContent = '共 ' + state.epg.length + ' 个节目表源';
    if (state.epg.length === 0) {
        list.innerHTML = '<div class="empty">暂无节目表源，先在上方添加</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < state.epg.length; i++) {
        var s = state.epg[i];
        html += '<div class="item">'
            + '<div class="item-head"><div class="item-name">节目表源 ' + (i + 1) + '</div>'
            + '<span class="badge badge-on">优先 ' + (i + 1) + '</span></div>'
            + '<div class="item-url">' + esc(s.url) + '</div>'
            + '<div class="item-actions">'
            + '<button class="btn sm ghost" data-act="edit" data-id="' + esc(s.id) + '">编辑</button>'
            + '<button class="btn sm ghost" data-act="copy" data-id="' + esc(s.id) + '">复制URL</button>'
            + '<button class="btn sm danger" data-act="del" data-id="' + esc(s.id) + '">删除</button>'
            + '</div></div>';
    }
    list.innerHTML = html;
}

async function deleteEpg(id) {
    var s = findEpg(id);
    if (!s) return;
    showConfirm('删除节目表源', '确定删除以下节目表源？删除后不可恢复。\n' + s.url, function () {
        doDeleteEpg(id);
    });
}

async function doDeleteEpg(id) {
    try {
        await api('/api/epg/sources/' + id, { method: 'DELETE' });
        toast('已删除节目表源');
        loadEpg();
    } catch (e) {
        toast('删除失败: ' + e.message, true);
    }
}

function findEpg(id) {
    for (var i = 0; i < state.epg.length; i++) {
        if (state.epg[i].id === id) return state.epg[i];
    }
    return null;
}

async function toggleEpgEnabled() {
    var enabled = byId('epgEnabled').checked;
    try {
        await api('/api/epg/status', formBody({ enabled: enabled }));
        toast(enabled ? 'EPG 已开启' : 'EPG 已关闭');
    } catch (e) {
        byId('epgEnabled').checked = !enabled;
        toast('设置失败: ' + e.message, true);
    }
}

async function refreshEpg() {
    try {
        await api('/api/epg/refresh', formBody({}));
        toast('已触发后台拉取，源多时约需十几秒');
    } catch (e) {
        toast('触发失败: ' + e.message, true);
    }
}

// ==================== 编辑面板 ====================

function openSheet(config) {
    state.sheet = config;
    byId('sheetTitle').textContent = config.title;
    byId('sheetNameField').style.display = config.showName ? '' : 'none';
    byId('sheetName').value = config.name || '';
    byId('sheetUrl').value = config.url || '';
    byId('sheetMask').classList.add('open');
    setTimeout(function () { byId('sheetUrl').focus(); }, 60);
}

function closeSheet() {
    state.sheet = null;
    byId('sheetMask').classList.remove('open');
}

// ==================== 确认弹窗（Modal） ====================

function showConfirm(title, message, onOk) {
    byId('confirmTitle').textContent = title;
    byId('confirmBody').textContent = message;
    byId('confirmMask')._onOk = onOk;
    byId('confirmMask').classList.add('open');
}

function closeConfirm() {
    byId('confirmMask').classList.remove('open');
    byId('confirmMask')._onOk = null;
}

async function submitSheet() {
    if (!state.sheet) return;
    var url = byId('sheetUrl').value.trim();
    var name = byId('sheetName').value.trim();
    if (!url) { toast('URL 不能为空', true); return; }
    if (state.sheet.showName && !name) { toast('名称不能为空', true); return; }
    try {
        if (state.sheet.type === 'source') {
            var old = findSource(state.sheet.id);
            await api('/api/sources/' + state.sheet.id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: formParams({ name: name, url: url, enabled: old ? old.enabled : true })
            });
            toast('已保存: ' + name);
            loadSources();
        } else {
            await api('/api/epg/sources/' + state.sheet.id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: formParams({ url: url })
            });
            toast('已保存节目表源');
            loadEpg();
        }
        closeSheet();
    } catch (e) {
        toast('保存失败: ' + e.message, true);
    }
}

function copyUrl(url) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(function () {
            toast('URL 已复制');
        }, function () { fallbackCopy(url); });
    } else {
        fallbackCopy(url);
    }
}

function fallbackCopy(url) {
    var ta = document.createElement('textarea');
    ta.value = url;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    toast(ok ? 'URL 已复制' : '复制失败，请长按手动复制', ok ? false : true);
}

// ==================== 事件绑定 ====================

byId('tabSources').addEventListener('click', function () { switchTab('sources'); });
byId('tabEpg').addEventListener('click', function () { switchTab('epg'); });

function switchTab(tab) {
    byId('tabSources').className = 'tab' + (tab === 'sources' ? ' active' : '');
    byId('tabEpg').className = 'tab' + (tab === 'epg' ? ' active' : '');
    byId('view-sources').className = 'view' + (tab === 'sources' ? '' : ' hidden');
    byId('view-epg').className = 'view' + (tab === 'epg' ? '' : ' hidden');
}

byId('refreshBtn').addEventListener('click', function () {
    loadAll();
    toast('已刷新');
});

byId('addSourceForm').addEventListener('submit', async function (e) {
    e.preventDefault();
    var name = byId('sourceName').value.trim();
    var url = byId('sourceUrl').value.trim();
    if (!name || !url) { toast('名称和 URL 都要填写', true); return; }
    try {
        await api('/api/sources', formBody({ name: name, url: url }));
        byId('sourceName').value = '';
        byId('sourceUrl').value = '';
        toast('已添加: ' + name);
        loadSources();
    } catch (err) {
        toast('添加失败: ' + err.message, true);
    }
});

byId('addEpgForm').addEventListener('submit', async function (e) {
    e.preventDefault();
    var url = byId('epgUrl').value.trim();
    if (!url) { toast('URL 不能为空', true); return; }
    try {
        await api('/api/epg/sources', formBody({ url: url }));
        byId('epgUrl').value = '';
        toast('已添加节目表源');
        loadEpg();
    } catch (err) {
        toast('添加失败: ' + err.message, true);
    }
});

byId('sourceList').addEventListener('click', function (e) { handleListClick(e, 'source'); });
byId('epgList').addEventListener('click', function (e) { handleListClick(e, 'epg'); });

function handleListClick(e, type) {
    var btn = e.target.closest('button[data-act]');
    if (!btn) return;
    var act = btn.getAttribute('data-act');
    var id = btn.getAttribute('data-id');
    if (act === 'del') {
        if (type === 'source') deleteSource(id); else deleteEpg(id);
    } else if (act === 'toggle') {
        toggleSource(id);
    } else if (act === 'copy') {
        var item = type === 'source' ? findSource(id) : findEpg(id);
        if (item) copyUrl(item.url);
    } else if (act === 'edit') {
        if (type === 'source') {
            var s = findSource(id);
            if (s) openSheet({ type: 'source', id: id, title: '编辑频道源', showName: true, name: s.name, url: s.url });
        } else {
            var g = findEpg(id);
            if (g) openSheet({ type: 'epg', id: id, title: '编辑节目表源', showName: false, url: g.url });
        }
    }
}

byId('epgEnabled').addEventListener('change', toggleEpgEnabled);
byId('epgRefreshBtn').addEventListener('click', refreshEpg);

byId('sheetCancel').addEventListener('click', closeSheet);
byId('sheetOk').addEventListener('click', submitSheet);
byId('sheetMask').addEventListener('click', function (e) {
    if (e.target === byId('sheetMask')) closeSheet();
});

byId('confirmCancel').addEventListener('click', closeConfirm);
byId('confirmOk').addEventListener('click', function () {
    var onOk = byId('confirmMask')._onOk;
    closeConfirm();
    if (onOk) onOk();
});
byId('confirmMask').addEventListener('click', function (e) {
    if (e.target === byId('confirmMask')) closeConfirm();
});

document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    if (byId('confirmMask').classList.contains('open')) closeConfirm();
    else if (state.sheet) closeSheet();
});

// ==================== 启动 ====================

async function loadAll() {
    await Promise.all([loadSources(), loadEpg()]);
}

loadAll();
</script>
</body>
</html>"""
    }
}

