# Operator - On-Device AI Agent for Android UI Automation
[中文](./README_CN.md)

![Build Status](https://img.shields.io/github/actions/workflow/status/xjyzs/operator-on-android/.github/workflows/android.yml?style=flat-square)

## Call AI APIs directly from your phone. No PC connection required—let the AI operate your device untethered!

## Video Demo (Android 17, Stock Android for PC)
https://github.com/user-attachments/assets/6dd29d4e-1f6b-48eb-8dc8-359d484581bc

<img height="360" alt="demo" src="https://github.com/user-attachments/assets/80426d87-146c-4651-828f-2f66617cff21" />


## Key Features
- **Virtual Screen Support:** The AI operates on a background virtual screen without occupying your main display. You can chat, browse, or watch videos while the AI works in the background.
- **Accessibility-based Layout Parsing:** Leverages Android Accessibility Services to parse screen layouts, providing coordinates of UI elements and richer context for the AI.
- **Token Consumption Tracking:** Built-in stats to monitor your API usage and token costs.
- **Visual Click Markers:** Highlights the target of the AI's last action, making it easier to debug and correct if the model misses the mark.
- **Broad App Compatibility:** Supports launching and controlling any user-installed application on the device.
- **Status Bar Integration:** Task execution status integrates into the system status bar / dynamic island on devices running Android 16+.
- **Highly Optimized Performance:** Deeply optimized virtual screen execution ensures smooth background operations.

## Quick Start

> **Prerequisite:** Please ensure your device is **rooted**.

### 1. Download and Install
Go to [Releases](https://github.com/xjyzs/AutoGLM-UI/releases) and download the APK compatible with your device architecture:
* **Android 8.0+** (standard devices): `app-arm64Minsdk26-release.apk`
* **Android 10+**: `app-arm64Minsdk29-release.apk`
* **Android 15+**: `app-arm64Minsdk35-release.apk`
* **Emulators**: `app-x86_64-release.apk`
* **Unsure?** Download: `app-universal-release.apk`

### 2. Configuration
Follow the in-app prompts to configure your API settings. Fill in the `URL`, `API Key`, and the `Model Name` provided by your LLM/VLM service provider.

* *Tip:* Click the arrow icon next to the model name field. If supported by your model provider, the app can automatically search and fetch available models using the keyword you entered.

### 3. Send Commands
* Tap the input box at the bottom, enter your instructions, and tap the **"arrow"** icon to send.
* If you manually intervene and take over the device, press the **"arrow"** button again to let the AI resume execution.

## Tested Devices

| Android Version | OS / ROM | Limitations / Status |
| :--- | :--- | :--- |
| **17** | Stock Android for PC | When the screen is locked and turned off, the screen goes black and touch input cannot be injected. |
| **16** | ColorOS 16 | Fully functional; no issues observed. |
| **15** | HyperOS 2 | Touch input injection fails when the screen is turned off. |