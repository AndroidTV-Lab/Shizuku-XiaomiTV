# Shizuku-XiaomiTV

基于 [Shizuku](README-original.md) 的小米电视分支：利用小米电视固件中的 `TvService` 诊断通道以 root 身份启动 Shizuku 服务，并在安装为系统应用后实现开机静默启动。

## 原理

小米电视的 `mitv.internal.ITvService`（`TvService`）暴露了一个诊断入口，传入脚本路径后会交给运行在 `u:r:misysdiagnose:s0`、uid=0 的诊断组件执行，且未做充分鉴权：

```sh
service call TvService 4400 s16 s s16 /data/local/tmp/script.sh
```

本分支在这个通道上增加了启动器：

1. App 进程启动时检查 `service list` 中是否存在 `TvService` / `mitv.internal.ITvService`
2. 用无害探测脚本确认通道确实以 uid=0 执行
3. 生成运行时脚本，通过 `app_process` 启动 `rikka.shizuku.server.ShizukuService`
4. 服务端以 root 运行，Binder 回传到管理端

普通数据安装的包在开机后会被小米电视置为 `stopped=true`，因此收不到 `BOOT_COMPLETED`。App 内提供“安装为系统应用”入口，把 APK 和 `librish.so` 复制到 `/system` 并移除数据安装；重启后系统应用可以正常接收开机广播并静默启动服务。

系统应用无法使用常规的 `nativeLibraryDir`，因此 `librish.so` 需要放到 `/system/lib`（64 位为 `/system/lib64`），启动参数中的 `shizuku.library.path` 指向该目录。

## 功能

- App 启动时自动检测 TvService 并启动服务
- “安装为系统应用”入口（仅在 TvService 可用且当前不是系统应用时显示）
- 安装完成后 Toast 提示并自动重启
- 系统应用开机自动启动
- 简体中文、繁体中文、英文文案

## 已验证设备

| 设备                            | 系统 | 结果 |
|---------------------------------|---|---|
| 小米 RDEMI L75MA-RAE（`mulan`） | Android 9 / API 28，OS3.0.104.0.PSTAATV，armeabi-v7a | TvService 提权、root 服务、App 启动自动拉起、系统应用开机静默启动均验证通过 |

## 可能适用的设备

- 运行小米电视系统（Mi TV OS 3 等）且存在 `TvService` / `mitv.internal.ITvService` 的小米电视，尤其是 Amlogic 平台机型
- 是否可用由 App 的预检决定：`service list` 中存在该服务，且探测脚本能以 uid=0 执行
- 非小米、非该固件的设备不适用；其他机型未逐一验证

## 使用

1. 安装并打开 App，首次启动会自动尝试通过 TvService 启动服务
2. 如需开机静默启动：菜单 → Install as system app → 确认 → 自动重启
3. 重启后 App 作为系统应用运行，服务自动启动

## 构建

```bash
git submodule update --init --recursive
./gradlew :manager:assembleRelease
```

要求：Android SDK、NDK 29.0.13113456、CMake 3.31+、JDK 21。

## 注意

- 该通道是固件特定的，系统更新可能修复
- 安装为系统应用会写入 `/system`，OTA 后可能丢失
- 仅用于你自己拥有的设备

原 fork 的 README（功能、下载、隐私说明等）见：[README-original.md](README-original.md)
