# My TV Broadcast

一款基于 Android TV (Leanback) 的 IPTV 直播应用，支持通过 HTTP 服务器远程管理信号源和频道。| An Android TV (Leanback) based IPTV streaming app with remote signal source and channel management via HTTP server.

---

## 功能特点 | Features

- **IPTV 播放 | IPTV Playback**: 支持 HLS (.m3u8) 和标准 HTTP 流 | Support for HLS (.m3u8) and standard HTTP streams
- **频道管理 | Channel Management**: D-pad 遥控器浏览和播放 | Browse and play with D-pad remote control
- **信号源管理 | Signal Source Management**: 添加、编辑、删除信号源 | Add, edit, delete signal sources
- **内置 HTTP 服务器 | Built-in HTTP Server**: 通过手机浏览器远程管理 | Remote management via mobile browser
- **二维码访问 | QR Code Access**: 手机扫码快速访问 | Quick access via QR code scanning
- **自动重连 | Auto-reconnect**: 信号不稳定时自动重试 | Automatic retry on signal instability

---

## 系统要求 | Requirements

- Android TV 设备 (Android 5.0+) | Android TV device (Android 5.0+)
- ExoPlayer/Media3
- NanoHTTPD (嵌入式 HTTP 服务器 | embedded HTTP server)

---

## 构建安装 | Build & Install

```bash
# 克隆仓库 | Clone the repository
git clone git@github.com:ousheobin/my-tv-broadcast.git

# 构建 | Build
./gradlew assembleDebug

# 安装到 TV 设备 | Install to TV device
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用说明 | Usage

### 添加信号源 | Add Signal Source

设置 → 信号源管理 → 输入名称和 M3U URL → 添加
Settings → Signal Source Management → Enter name and M3U URL → Add

### 远程管理 | Remote Management

1. 在设置中开启 HTTP 服务 | Enable HTTP server in Settings
2. 用手机扫描二维码 | Scan QR code with phone
3. 通过网页界面管理频道和信号源 | Manage channels via web interface

### 播放控制 | Playback Controls

| 按键 | Button | 功能 | Action |
|------|--------|------|--------|
| ↑/↓ | UP/DOWN | 切换频道 | Switch channel |
| 确认/播放暂停 | CENTER/PLAY_PAUSE | 暂停/恢复 | Pause/Resume |

---

## 技术栈 | Tech Stack

Kotlin + AndroidX Leanback + Media3 ExoPlayer + NanoHTTPD + ZXing

---

## 项目结构 | Project Structure

```
app/src/main/java/com/steve/mytvbroadcast/
├── data/           # 数据层 | Data layer
├── server/         # HTTP 服务器 | HTTP server
├── ui/             # UI 层 | UI layer
└── util/           # 工具类 | Utilities
```
