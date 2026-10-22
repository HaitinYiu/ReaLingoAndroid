# ReaLingo for Android

实时语音同声传译 App。功能对 [noSugarK/ReaLingo](https://github.com/noSugarK/ReaLingo)（Tauri 2 + Rust + Vue 3 桌面版）的完整 Android 移植：同样的四家实时翻译接口、同样的轮次配对逻辑、同样的重采样与朗读管线，界面改为手机竖屏 + 悬浮字幕窗。

## 功能对照

| 桌面版 | Android 版 | 说明 |
| --- | --- | --- |
| 麦克风 | ✅ 麦克风 | `AudioRecord` 48 kHz 单声道，优先 `UNPROCESSED` 音源 |
| 系统声音 | ✅ 系统声音 | `AudioPlaybackCapture`（Android 10+，需录屏授权）——只录其他 App 的播放，自动排除自家朗读 |
| 本地音频文件 | ✅ 音频文件 | SAF 选取 → `MediaExtractor`+`MediaCodec` 解码 → 按真实时长匀速送入 |
| Qwen3.5 / Qwen3.8 / OpenAI / Gemini | ✅ 四选一 | 协议层逐字段对照原项目 `config.rs` 移植 |
| 源语言自动检测 | ✅ | 与各接口的能力一致（Qwen3.5 可手选，其余固定 auto） |
| 朗读译文 + 47 音色 | ✅ | Qwen 音色表完整搬入；无语音输出的目标语言自动置灰 |
| 独立置顶字幕窗 | ✅ 悬浮字幕 | `TYPE_APPLICATION_OVERLAY`，可拖动、锁定点击穿透 |
| 字幕样式（双语/仅译文/仅原文、字号、颜色、描边、对齐、不透明度） | ✅ | 全部保留，另加「自动加高 / 单行滚动」 |
| 系统托盘 | ✅ 常驻通知 | 通知栏可停止、开关字幕 |
| 中英文切换、浅/深/跟随系统 | ✅ | |
| 导出 TXT / SRT | ✅ | 导出到缓存后走系统分享 |
| 翻译历史（JSONL 单场一文件，可搜索/导出/删除） | ✅ | 目录 `files/history/` |
| 原始事件查看器 | ✅ | 最近 300 帧，音频折叠为字节数 |
| 关于面板 | ✅ | 版本与原仓库链接 |

## 架构

```
麦克风 / 系统声音 / 音频文件
        │  48 kHz float
        ▼
   Chunker ── 降混单声道 → 窗化 sinc 重采样 (16/24 kHz) → i16 → 100 ms 分块
        ▼
   OkHttp WebSocket ── base64 + 各接口音频帧
        │                    ▲
        │              session.update / setup
        ▼
   帧解析 → 事件归一化 (TextStream) → 轮次配对 → Compose UI + 悬浮字幕
        │
        └→ 24 kHz PCM 增量 → AudioTrack(48 kHz)
```

- `core/Protocol.kt`：`wsUrl` / `session.update` / 音频帧 / 结束帧 / 结束判定的全部差异，字段命名与原项目一致（都是对活接口逐字段探测出来的，不要“顺手整理”）。
- `core/TextStream.kt`：把三家的帧归一化成 `status/link/source/target/speech/warn` 事件；Qwen 按 item id 配对，OpenAI 与 Gemini 只有两条增量流，按句末标点切轮（`.!?` 需后跟空白，`。！？…` 立即生效；150 字无句末强制切分）。
- `core/Resampler.kt`：32 抽头 Blackman 窗 sinc，截止频率取两端奈奎斯特较小者，任意比率抗混叠（48000/16000=3，44100/16000=2.75625）。
- `core/Session.kt`：连接 → 发 `session.update` → 上行 100 ms 帧 → 停止时补静音（Gemini 3 秒）→ 发结束帧 → 读到 `session.finished/closed`；Gemini 没有结束信号，改用「停止后 4 秒无文字也无非静音音频」收尾。
- `core/Engine.kt`：`stream.ts` 的轮次模型（`turns` / `byInput` / `publish` / `settle`）与会话状态机。
- `service/`：前台服务（`microphone` / `mediaProjection` 类型）、常驻通知、悬浮字幕 Controller。

## 构建

需要 JDK 17、Android SDK（platform 34、build-tools 34.0.0）。

```bash
# local.properties 里写自己的 SDK 路径
echo 'sdk.dir=/path/to/android-sdk' > local.properties
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开 App → 右上角设置 → 选接口、填对应的 API Key（Qwen 走阿里云百炼，区域可选北京/新加坡；也可填工作空间 ID 或自定义接入地址）。
2. 选源语言/目标语言。Qwen3.5 的源语言可选「自动检测」或指定；Qwen3.8 / OpenAI / Gemini 固定自动检测。
3. 需要朗读就打开「朗读译文」，Qwen 可选 47 种音色。
4. 点麦克风开始。字幕会实时出现在主页，打开「悬浮字幕」后会浮在其他 App 上方。
5. 用麦克风时请戴耳机，否则扬声器的声音会被收回去再翻一遍；录系统声音时自家朗读会被系统自动排除。

## 权限说明

- `RECORD_AUDIO`：麦克风 / 系统声音采集。
- `SYSTEM_ALERT_WINDOW`：悬浮字幕。
- 录屏授权（MediaProjection）：仅「系统声音」模式需要，用于 `AudioPlaybackCapture`。
- `POST_NOTIFICATIONS`：常驻通知（Android 13+）。
- API Key 只存本机应用私有 SharedPreferences，不经过任何中转。

## 许可

移植自 [noSugarK/ReaLingo](https://github.com/noSugarK/ReaLingo)，MIT License，原始版权声明见 `LICENSE`。
