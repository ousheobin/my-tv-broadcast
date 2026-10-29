# My TV Broadcast

一款 Android TV 上的 IPTV 直播应用：加载 M3U 频道源，用遥控器看电视直播，自带 EPG 节目表，并可通过手机浏览器远程管理频道源和节目表。| An IPTV live-streaming app for Android TV: load M3U sources, watch live TV with a remote, with built-in EPG and a mobile-friendly web console for remote management.

---

## 功能 | Features

- **直播播放 | Playback**: HLS (.m3u8) 与标准 HTTP 流，信号不稳自动重连 | HLS and standard HTTP streams, auto-reconnect on unstable signals
- **遥控器操作 | D-pad UX**: Leanback 界面浏览、选台、上下切台 | Browse and switch channels with the remote
- **EPG 节目表 | Program Guide**: 多源 XMLTV 合并检索，按源顺序优先匹配 | Multi-source XMLTV merging with ordered priority
- **网页管理台 | Web Console**: 设置中开启 HTTP 服务后，手机扫码即可增删改频道源与节目表、触发节目表刷新（移动端优先，零外部依赖） | Enable the HTTP server in Settings, scan the QR code to manage sources and refresh EPG from a phone (mobile-first, zero dependencies)

---

## 开发 | Development

技术栈 | Tech stack: Kotlin + AndroidX Leanback + Media3 ExoPlayer + NanoHTTPD + ZXing

环境要求 | Requirements: Android 5.0+，Android Studio / JDK 17+

```bash
# 克隆 | Clone
git clone git@github.com:ousheobin/my-tv-broadcast.git

# 构建 | Build
./gradlew assembleDebug

# 安装到 TV 设备 | Install to TV device
adb install app/build/outputs/apk/debug/app-debug.apk
```

```
app/src/main/java/com/steve/mytvbroadcast/
├── data/           # 频道、EPG、信号源等数据层 | Data layer (channels, EPG, sources)
├── server/         # 内置 HTTP 服务器与网页管理台 | Embedded HTTP server & web console
├── ui/             # TV 界面与播放页 | TV UI and playback
└── util/           # 工具类 | Utilities
```

设计规范见 `docs/design-spec.md`。| Design tokens: see `docs/design-spec.md`.

---

## 致谢 | Acknowledgements

本项目构建于以下开源项目之上，感谢它们的作者与维护者：| Built upon these great open source projects:

| 项目 Project | 许可证 License |
|--------------|----------------|
| AndroidX（Leanback / Media3 ExoPlayer / Compose 等） | Apache-2.0 |
| Kotlin & kotlinx.coroutines | Apache-2.0 |
| OkHttp (Square) | Apache-2.0 |
| ZXing / zxing-android-embedded（二维码） | Apache-2.0 |
| NanoHTTPD（嵌入式 HTTP 服务器） | BSD-3-Clause |
| Glide（图片加载） | BSD，含部分 MIT 与 Apache-2.0 |

EPG 节目表数据默认来自 [epg.pw](https://epg.pw/)，感谢其公开提供。| Default EPG data is provided by [epg.pw](https://epg.pw/).
