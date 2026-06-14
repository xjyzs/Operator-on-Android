# Operator - 能直接操作手机的 AI Agent
![构建状态](https://img.shields.io/github/actions/workflow/status/xjyzs/operator-on-android/.github/workflows/android.yml?style=flat-square)

## 手机直接调用 AI API ，无需连接电脑，让 AI 直接操作你的手机!

## 视频演示(Android 17 原生 Android for PC)
https://github.com/user-attachments/assets/6dd29d4e-1f6b-48eb-8dc8-359d484581bc

<img height="360" alt="demo" src="https://github.com/user-attachments/assets/80426d87-146c-4651-828f-2f66617cff21" />


## 亮点
- 支持虚拟屏，AI 操作时**不占用主屏**，你可在 AI 干活的同时刷视频、聊天...
- 支持统计 Tokens 消耗
- 用无障碍获取屏幕布局，为 AI 提供关键元素坐标、构建上下文
- 支持标注 AI 上次点击位置，便于 AI 点歪时纠正
- 支持设备上已安装的所有可启动应用
- 执行状态可在 Android 16+ 设备上岛
- 深度优化虚拟屏执行效率，运行流畅

## 快速开始
请先确保你的设备拥有 Root 权限

**前往[Releases](https://github.com/xjyzs/AutoGLM-UI/releases)下载符合你设备的 apk**
> 对于一般设备，Android 8.0+ 下载`app-arm64Minsdk26-release.apk`  
> Android 10+ 下载`app-arm64Minsdk29-release.apk`  
> Android 15+ 下载`app-arm64Minsdk35-release.apk`  
> 模拟器可下载`app-x86_64-release.apk`  
> 如果不清楚，下载`app-universal-release.apk`

按照提示配置 API ，然后填入模型提供商提供的`URL`、`Key`以及`模型名称`

- 点击`模型名称`后面的箭头后，如果模型提供商支持，应用能够填入以`模型名称`的内容为关键字搜索可用模型

### 给 AI 发送指令

- 点击下方文本框，输入指令，按"箭头"发送
- 用户完成接管后，按"箭头"继续

## 已测试设备
| Android 版本 | 系统                | 系统限制               |
|------------|-------------------|--------------------|
| 17         | 原生 Android for PC | 锁屏且关闭屏幕时会黑屏且无法注入触控 |
| 16         | ColorOS 16        | 完美运行               |
| 15         | HyperOS 2         | 关闭屏幕时会无法注入触控       |
