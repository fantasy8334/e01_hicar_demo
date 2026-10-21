# E01 HiCar 车机端工程（Android 5.1 / minSdk 22）

> ⚠️ 说明
> - 本项目为**个人技术学习研究**用途，华为 HiCar 为私有协议，**禁止商用**。
> - 目标设备：吉利 E01 车机（Android 5.1，API 22）。
> - 这套源码需在**装有 Flutter + Android SDK 的电脑**上编译出 APK（本仓库环境不含构建工具链）。

## 一、目录结构（源码）
```
hicar_e01_demo_src/
├── lib/main.dart                                   # Flutter 主界面
├── AndroidManifest.xml                             # 覆盖用（含 5.1 权限）
├── app_build.gradle                                # 覆盖用（minSdk22 + CMake）
├── kotlin/com/example/hicar_e01_demo/
│   ├── MainActivity.kt                             # Flutter 入口 + JNI 加载 + 生命周期
│   ├── HicarStateMachine.kt                        # 链路状态机（BLE→WiFi→TCP 自动联动）
│   ├── HicarLogger.kt                              # 本地文件日志
│   ├── ble/HicarBleServer.kt                       # BLE GATT Server + 握手 + AES
│   ├── ble/HicarBleFragmentAssembler.kt            # BLE 分片/重组
│   ├── ble/HicarAesCrypto.kt                       # AES-128-CBC
│   ├── wifi/HicarWifiP2pManager.kt                 # P2P GO/STA + 启动 TCP
│   ├── wifi/HicarTcpServer.kt                      # TCP 收 H264/PCM + CRC
│   ├── codec/HicarMediaCodec.kt                    # MediaCodec 硬解
│   └── video/                                     # Flutter PlatformView + TextureView
│       ├── HicarVideoViewFactory.kt
│       ├── HicarVideoPlatformView.kt
│       ├── HicarVideoTextureView.kt
│       └── VideoCodecHolder.kt
└── cpp/
    ├── CMakeLists.txt
    ├── native_lib.cpp                              # JNI 桥（音频）
    ├── opensl_audio.h / opensl_audio.cpp           # OpenSL ES 音频播放
```

## 二、生成 APK 步骤（在你的电脑上，需已装 Flutter + Android SDK）

### 1. 创建 Flutter 工程外壳
```bash
flutter create --org com.example --project-name hicar_e01_demo hicar_e01_demo
cd hicar_e01_demo
```

### 2. 拷贝本源码并覆盖
```bash
SRC=/path/to/hicar_e01_demo_src

# 2.1 覆盖 Flutter 主界面
cp $SRC/lib/main.dart lib/main.dart

# 2.2 覆盖 AndroidManifest
cp $SRC/AndroidManifest.xml android/app/src/main/AndroidManifest.xml

# 2.3 覆盖 app/build.gradle（minSdk22 + CMake）
cp $SRC/app_build.gradle android/app/build.gradle

# 2.4 拷贝 Kotlin 源码
mkdir -p android/app/src/main/kotlin/com/example/hicar_e01_demo
cp -r $SRC/kotlin/com/example/hicar_e01_demo/* \
      android/app/src/main/kotlin/com/example/hicar_e01_demo/

# 2.5 拷贝 C++ 源码
cp -r $SRC/cpp android/app/src/main/cpp
```

> 注：`flutter create` 生成的 `android/app/src/main/kotlin/.../MainActivity.kt` 会被本工程的
> `MainActivity.kt` 覆盖（包名一致 `com.example.hicar_e01_demo`）。

### 3. 依赖与构建
```bash
flutter pub get
flutter build apk --release
```
产物：`build/app/outputs/flutter-apk/app-release.apk`

### 4. （可选）仅 arm 32 位包，进一步缩小
```bash
flutter build apk --release --target-platform android-arm
```

## 三、Android 版本注意事项（5.1 / E01）
1. `minSdk=22`，无运行时权限模型，Manifest 静态声明即可。
2. 不要声明 `BLUETOOTH_SCAN/CONNECT/ADVERTISE`（Android 12 专属，5.1 不存在）。
3. MediaCodec H.264 硬解 API16+ 可用；E01 芯片建议 H.264 Baseline。
4. WiFi P2P GO 在部分 E01 ROM 被阉割，代码已降级 STA（连接手机热点）。
5. 若硬件不支持 BLE 外设广播，只能走有线 USB 通道（本项目未含 USB 路径）。

## 四、手机端对接（协议）
详见聊天中的《HiCar 车机-手机通信协议 V1.0》：
- BLE GATT：Service `0000FF00-...`，写 `0000FF01-...`，Notify `0000FF02-...`
- 握手：HELLO→PEER_RANDOM→HANDSHAKE_DONE，密钥 = 双方随机数 XOR
- 握手后所有 GATT 报文 AES-128-CBC（IV(16)+密文）
- TCP：端口 12345，帧头 `[type:2][len:2][crc32:4]`，type 0x0001=视频 / 0x0002=PCM

## 五、已知限制（生产前需补齐）
- 视频 PTS 暂用系统时间，未接入真实 PTS 音画同步
- STA 模式未实现手机热点动态接入（需 BLE 下发 SSID/密码）
- 无开机自启、息屏保活、方控按键映射
- 无 OTA、崩溃上报、签名合规流程
