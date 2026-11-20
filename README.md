# Operator - 能后台操作手机的 AI Agent
![构建状态](https://img.shields.io/github/actions/workflow/status/xjyzs/operator-on-android/.github/workflows/android.yml?style=flat-square)

**简体中文** | [English](README_EN.md)

## 一个强大的 AI 界面操作与自动化框架，借助 Shizuku(ADB) 或 Root 的系统级能力，为你的 Android 设备加入能真正做事的智能助理
## 界面展示
> 提示词: 在GitHub找今天的热点，用mt管理器放到md文件里
<img height="360" alt="界面" src="https://github.com/user-attachments/assets/8c1824ae-afcf-4456-86db-b9e1e6b0c643" />
<img height="360" alt="菜单" src="https://github.com/user-attachments/assets/fac6e551-cf6c-4122-a9b7-c6eabf50e0eb" />
<img width="158" alt="悬浮窗" src="https://github.com/user-attachments/assets/9dca5a57-8e23-45fd-8342-f350209ad618" />


## 亮点
- 支持虚拟屏，AI 操作时**不占用主屏**，你可在 AI 干活的同时刷视频、聊天...
- 深度优化执行步骤，**所需步数少，成功率高，工具执行快**
- 执行状态可在 Android 16+ 设备**上岛**，即状态胶囊与状态栏/实时活动(灵动岛/流体云)，便于在后台查看执行状态
- 拥有**反检测**设计(如模拟人类触屏曲线、对指定应用不获取无障碍节点)
- 拥有精美、现代的界面和丝滑流畅的动画，且启动迅速
- 支持统计 Tokens 消耗
- 支持远程管理，出门在外也能查看家中设备的 AI 执行状态并下发命令

## 一些执行方面的优化内容
- **工具调用**完善，提升模型执行能力
- 用无障碍获取屏幕布局，为 AI 提供关键元素坐标以及上下文文本
- **上下文管理**完善，比如应用不提供无障碍节点时图片多保留一轮
- 标注 AI 上次点击位置，便于 AI 点歪时纠正
- 支持设备上已安装的所有可启动应用

## 视频演示(Android 17 原生 Android for PC)(旧版)
https://github.com/user-attachments/assets/6dd29d4e-1f6b-48eb-8dc8-359d484581bc

## 快速开始
**前往[Releases](https://github.com/xjyzs/AutoGLM-UI/releases)下载符合你设备的 apk**

按照提示配置权限 ，然后填入模型提供商提供的`URL`、`Key`以及`模型名称`

回到主页，点击下方的输入框，开始尽情使用吧！

## 常见问题
Q: 为什么 **Shell 权限**无法授予?

A: 此应用需要你的设备**已 Root**或已安装 **Shizuku** 并激活

Q: 使用什么 **AI API**?

A: 本工具支持 绝大多数 **OpenAI 格式**的 API, 只要模型支持**视觉**(识图)即可

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
