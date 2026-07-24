# sherpa-onnx 集成说明

## FFmpegKitNext

FFmpegKitNext 8.1.0 的本地 Maven 产物位于：

```text
app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next/8.1.0/
```

该 AAR 使用上游 `android-r27d` profile 在 API 24 上构建，仅包含
`armeabi-v7a` 和 `arm64-v8a`。重新生成时使用：

```bash
./nix-android.sh \
  -p android-r27d \
  --enable-android-zlib \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64
```

AAR SHA-256：

```text
F8C1168AF4D48625F1DB6250AC553657715F27EA705FC1556CC223CC8A062897
```

## sherpa-onnx

## ✅ 集成已完成

sherpa-onnx v1.13.4 已成功集成到项目中。

### 集成内容

1. **Kotlin API 源码**
   - 位置：`app/src/main/java/com/k2fsa/sherpa/onnx/`
   - 包含所有必需的 API 类（OfflineRecognizer、OfflineStream 等）

2. **Native 库文件**
   - 位置：`app/src/main/jniLibs/`
   - 支持架构：
     - arm64-v8a (主流 64 位设备)
     - armeabi-v7a (32 位设备)
     - x86 (模拟器)
     - x86_64 (64 位模拟器)

### 使用方法

直接在代码中导入使用：
```kotlin
import com.k2fsa.sherpa.onnx.*

val recognizer = OfflineRecognizer(config)
```

### 源文件

- 原始包：`sherpa-onnx-v1.13.4-android.tar.bz2` (45MB)
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4

### 注意事项

- 无需额外的 AAR 依赖
- Native 库会根据设备架构自动加载
- 确保 minSdk >= 24 (Android 7.0)
