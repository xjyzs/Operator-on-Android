# Operator - 能后台操作手机的 AI Agent
[English](./README.md) ![构建状态](https://img.shields.io/github/actions/workflow/status/xjyzs/operator-on-android/.github/workflows/android.yml?style=flat-square)

## 一个强大的 AI 界面操作与自动化框架，借助 Shizuku(ADB) 或 Root 的系统级能力，为你的 Android 设备加入能真正做事的智能助理
## 界面展示
> 提示词: 在GitHub找今天的热点，用mt管理器放到md文件里
<img height="360" alt="界面" src="https://github.com/user-attachments/assets/8c1824ae-afcf-4456-86db-b9e1e6b0c643" />

## 亮点
- 支持虚拟屏，AI 操作时**不占用主屏**，你可在 AI 干活的同时刷视频、聊天...
- **工具调用**完善，提升模型执行能力
- 用无障碍获取屏幕布局，为 AI 提供关键元素坐标以及上下文文本
- **上下文管理**完善，比如应用不提供无障碍节点时图片多保留一轮
- 标注 AI 上次点击位置，便于 AI 点歪时纠正
- 支持设备上已安装的所有可启动应用
- 执行状态可在 Android 16+ 设备**上岛**，即状态胶囊与状态栏/实时活动(灵动岛/流体云)，便于在后台查看执行状态
- 拥有精美、现代的界面和丝滑流畅的动画，且启动迅速
- 深度优化虚拟屏执行效率，运行流畅
- 支持统计 Tokens 消耗
- 支持远程管理，出门在外也能查看家中设备的 AI 执行状态并下发命令

## 视频演示(Android 17 原生 Android for PC)(旧版)
https://github.com/user-attachments/assets/6dd29d4e-1f6b-48eb-8dc8-359d484581bc

## 快速开始
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

## 已测试设备
| Android 版本 | 系统                | 系统限制               |
|------------|-------------------|--------------------|
| 17         | 原生 Android for PC | 锁屏且关闭屏幕时会黑屏且无法注入触控 |
| 16         | ColorOS 16        | 完美运行               |
| 15         | HyperOS 2         | 关闭屏幕时会无法注入触控       |
| 10         | HarmonyOS 3       | 无法使用虚拟屏            |

## Star History

<a href="https://www.star-history.com/?repos=xjyzs%2Foperator-on-android&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=xjyzs/operator-on-android&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=xjyzs/operator-on-android&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=xjyzs/operator-on-android&type=date&legend=top-left" />
 </picture>
</a>
