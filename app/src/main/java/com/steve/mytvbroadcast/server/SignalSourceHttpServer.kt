package com.steve.mytvbroadcast.server

import android.util.Log
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
            path == "/api/server/status" -> serveServerStatus()
            path == "/api/server/stop" -> handleStopServer()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveHtml(): Response {
        val html = buildBootstrapPage()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>My TV Broadcast - 信号源管理</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        :root {
            --blue-0: #e6f4ff;
            --blue-1: #bae0ff;
            --blue-2: #91caff;
            --blue-3: #69b1ff;
            --blue-4: #4096ff;
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
        }
        * { box-sizing: border-box; }
        body {
            background: var(--gray-2);
            color: var(--gray-9);
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            margin: 0;
            padding: 24px;
        }
        .container { max-width: 900px; margin: 0 auto; }
        h1 {
            font-size: 24px;
            font-weight: 600;
            color: var(--gray-9);
            margin: 0 0 24px 0;
        }
        .card {
            background: var(--gray-0);
            border: 1px solid var(--gray-4);
            border-radius: 8px;
            margin-bottom: 16px;
        }
        .card-body { padding: 20px 24px; }
        .card-title {
            font-size: 16px;
            font-weight: 500;
            color: var(--gray-8);
            margin: 0 0 16px 0;
        }
        .form-control {
            border: 1px solid var(--gray-4);
            border-radius: 6px;
            padding: 8px 12px;
            font-size: 14px;
            width: 100%;
        }
        .form-control:focus {
            border-color: var(--blue-5);
            box-shadow: 0 0 0 2px var(--blue-1);
            outline: none;
        }
        .btn {
            border-radius: 6px;
            padding: 8px 16px;
            font-size: 14px;
            cursor: pointer;
            border: none;
        }
        .btn-primary {
            background: var(--blue-5);
            color: #fff;
        }
        .btn-primary:hover { background: var(--blue-6); }
        .btn-success {
            background: var(--gray-0);
            border: 1px solid var(--blue-5);
            color: var(--blue-5);
        }
        .btn-success:hover { background: var(--blue-0); }
        .btn-secondary {
            background: var(--gray-0);
            border: 1px solid var(--gray-4);
            color: var(--gray-7);
        }
        .btn-secondary:hover { background: var(--gray-2); color: var(--gray-8); }
        .btn-danger {
            background: var(--gray-0);
            border: 1px solid var(--gray-5);
            color: var(--gray-6);
        }
        .btn-danger:hover { border-color: #ff4d4f; color: #ff4d4f; background: #fff1f0; }
        .table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 0;
        }
        .table th {
            text-align: left;
            padding: 12px 16px;
            border-bottom: 1px solid var(--gray-4);
            font-weight: 500;
            color: var(--gray-7);
            font-size: 14px;
        }
        .table td {
            padding: 12px 16px;
            border-bottom: 1px solid var(--gray-3);
            font-size: 14px;
            color: var(--gray-8);
        }
        .table-hover tbody tr:hover { background: var(--blue-0); }
        .badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 12px;
        }
        .badge-success { background: var(--blue-0); color: var(--blue-6); }
        .badge-disabled { background: var(--gray-3); color: var(--gray-6); }
        .text-muted { color: var(--gray-6) !important; }
        .text-small { font-size: 12px; }
        .row { display: flex; gap: 12px; align-items: center; }
        .col { flex: 1; }
        .col-auto { flex: 0 0 auto; }
    </style>
</head>
<body>
    <div class="container">
        <h1>My TV Broadcast - 信号源管理</h1>

        <div class="card">
            <div class="card-body">
                <div class="card-title">添加信号源</div>
                <form id="addForm">
                    <div class="row">
                        <div class="col">
                            <input type="text" class="form-control" id="sourceName" placeholder="名称" required>
                        </div>
                        <div class="col" style="flex: 2">
                            <input type="url" class="form-control" id="sourceUrl" placeholder="M3U URL" required>
                        </div>
                        <div class="col-auto">
                            <button type="submit" class="btn btn-primary">添加</button>
                        </div>
                    </div>
                </form>
            </div>
        </div>

        <div class="card">
            <div class="card-body">
                <div class="card-title">信号源列表</div>
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>状态</th>
                            <th>名称</th>
                            <th>URL</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody id="sourceList">
                        <tr><td colspan="4" style="text-align:center;color:var(--gray-6)">加载中...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <script>
        let sources = [];

        async function loadSources() {
            try {
                const res = await fetch('/api/sources');
                sources = await res.json();
                renderSources();
            } catch (e) {
                document.getElementById('sourceList').innerHTML =
                    '<tr><td colspan="4" style="text-align:center;color:#ff4d4f">加载失败</td></tr>';
            }
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function renderSources() {
            const tbody = document.getElementById('sourceList');
            if (sources.length === 0) {
                tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--gray-6)">暂无信号源</td></tr>';
                return;
            }
            const html = sources.map(function(s) {
                const statusClass = s.enabled ? 'badge-success' : 'badge-disabled';
                const statusText = s.enabled ? '启用' : '禁用';
                const btnClass = s.enabled ? 'btn-secondary' : 'btn-success';
                const btnText = s.enabled ? '禁用' : '启用';
                return '<tr>' +
                    '<td><span class="badge ' + statusClass + '">' + statusText + '</span></td>' +
                    '<td>' + escapeHtml(s.name) + '</td>' +
                    '<td><span class="text-small text-muted">' + escapeHtml(s.url) + '</span></td>' +
                    '<td>' +
                        '<button class="btn btn-sm ' + btnClass + '" onclick="toggleSource(\'' + s.id + '\')">' + btnText + '</button>' +
                        ' <button class="btn btn-sm btn-danger" onclick="deleteSource(\'' + s.id + '\')">删除</button>' +
                    '</td>' +
                '</tr>';
            }).join('');
            tbody.innerHTML = html;
        }

        document.getElementById('addForm').onsubmit = async function(e) {
            e.preventDefault();
            const name = document.getElementById('sourceName').value;
            const url = document.getElementById('sourceUrl').value;

            const params = new URLSearchParams();
            params.append('name', name);
            params.append('url', url);

            try {
                const res = await fetch('/api/sources', {
                    method: 'POST',
                    body: params,
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
                });
                const json = await res.json();
                if (json.error) {
                    alert('添加失败: ' + json.error);
                    return;
                }
                document.getElementById('sourceName').value = '';
                document.getElementById('sourceUrl').value = '';
                loadSources();
            } catch (err) {
                alert('网络错误: ' + err.message);
            }
        };

        async function toggleSource(id) {
            const source = sources.find(function(s) { return s.id === id; });
            if (source) {
                const params = new URLSearchParams();
                params.append('name', source.name);
                params.append('url', source.url);
                params.append('enabled', !source.enabled);

                await fetch('/api/sources/' + id, { method: 'PUT', body: params });
                loadSources();
            }
        }

        async function deleteSource(id) {
            if (confirm('确定要删除这个信号源吗？')) {
                await fetch('/api/sources/' + id, { method: 'DELETE' });
                loadSources();
            }
        }

        loadSources();
    </script>
</body>
</html>"""
    }
}
