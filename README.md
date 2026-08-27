# LYSK-PS

《恋与深空》国服客户端的 LSPosed RSA 公钥替换与 Android `VpnService` 域名分流工具。

> 本项目仅用于个人研究、协议调试和自建服务测试，与叠纸游戏及其关联公司无关。
> 使用者应自行确认当地法律、服务条款和账号风险。

包名：`com.axuan.lyskps`  
目标游戏：`com.papegames.lysk.cn`  
最低系统：Android 7.0（API 24）

## 功能

- LSPosed/LSPatch 主进程 Hook，延迟替换 `global-metadata.dat` 中的 RSA 公钥。
- 关闭 RSA 开关时自动恢复官方公钥；模块主页通过 Shizuku 覆盖官方公钥，或删除 metadata 让游戏自行重新解包。
- Shizuku 集成包含 `ShizukuProvider`，首次使用恢复功能前请启动 Shizuku 并在提示中授权本模块。
- RSA 公钥块默认硬编码在 `Config.java`；首次注入游戏时会与 RSA 开关、偏移和延迟一起保存到游戏 prefs。启动悬浮设置框中的“RSA 设置”可修改私服公钥 Base64。
- 只接管指定应用的 `VpnService`；包名可填 `*`，表示除 LYSK-PS 自身外的全部应用。
- 域名规则同时匹配自身和子域名，未命中流量直接走系统网络。
- HTTP 代理模式：命中域名使用配置的 HTTP 代理。
- Web 重定向模式：命中域名连接配置的 HTTP/HTTPS 服务。
- 可选内置 HTTPS 包装器：针对 CONNECT 目标动态签发叶证书、终止 TLS，再把 HTTP 转发到后端。
- 代理地址和重定向服务地址独立保存；协议不匹配时显示警告。
- 深色 MD3 风格页面、圆角控件、独立可滚动的实时分流日志。
- 首次启动随机生成 CA/Leaf，不在 APK 中内置任何 TLS 证书或私钥。
- 支持导入 CA pair、Leaf pair；导出 Android 支持的 `.crt` 和 `subject_hash_old` `xxxxxxxx.0`。
- mdpi–xxxhdpi 传统/圆形图标及 API 26+ 自适应、主题图标。

VPN 数据面使用 [tun2proxy](https://github.com/tun2proxy/tun2proxy)，动态证书使用
[Bouncy Castle](https://www.bouncycastle.org/)。版本、许可证和来源见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 安装与使用

1. 从 Releases 下载 APK 并安装。
2. 要使用 RSA 替换功能，请在 LSPosed 启用此模块并勾选《恋与深空》，或使用 LSPatch
   将模块集成到游戏。只使用 VPN 功能时不要求 LSPosed。
3. 打开 `LYSK-PS`：
   - 配置过滤域名和 VPN 作用包名；
   - 选择 HTTP 代理或 Web 重定向；
   - 分别填写“上游代理地址”和“上游 HTTP 服务地址”；
   - 按需启用内置 HTTPS 包装器。
4. 点击“保存并启动”，首次使用时接受 Android VPN 授权。

### HTTPS 包装器证书

启用包装器后，首次启动会在应用私有目录随机生成 CA pair 和默认 Leaf pair。可在页面中：

- 分别导入 CA 证书/私钥、Leaf 证书/私钥；只有配对校验成功才会启用；
- 导出 `LYSK-PS-CA.crt`；
- 导出 Android 系统 CA 目录使用的 `xxxxxxxx.0` PEM；
- 主动重新随机生成 CA/Leaf。

新版 Android 请先导出 `.crt`，再前往系统“设置 → 安全/密码与安全 → 更多安全设置 →
加密与凭据 → 安装证书 → CA 证书”选择文件。不同 OEM 的菜单名称可能不同。

root 用户亦可将证书 `.0` 文件添加到系统 CA + Conscrypt APEX 中，但游戏默认信任用户证书，
因此不必这么做。

> CA 私钥位于应用私有目录。请只在受控设备上使用，不要公开分享导出的私钥或完整应用数据。

## 构建

要求：JDK 17/21、Android SDK Platform 34。项目已包含 Gradle Wrapper、Xposed API 编译依赖，
以及 `arm64-v8a`、`x86_64` 的 `libtun2proxy.so`。

Windows：

```powershell
.\gradlew.bat clean assembleRelease lintRelease --no-daemon
```

Linux/macOS：

```bash
./gradlew clean assembleRelease lintRelease --no-daemon
```

未签名 APK 输出到：

```text
app/build/outputs/apk/release/LYSK-PS-release.apk
```

图标可重复生成：

```bash
python tools/generate_icons.py
```

脚本读取 `branding/logo_source.jpg`，输出传统、圆形、自适应和主题图标资源。

## 已知限制

- Android 同一用户同一时刻只能启用一个 `VpnService`。
- HTTP 上游代理不提供通用 UDP 转发；QUIC/HTTP3 可能回退 TCP，也可能失败。
- 包装器关闭后，HTTPS 原始 TLS 只能重定向到真正支持 `https://` 的服务。
- RSA metadata 偏移与客户端版本绑定；当前默认值为 `0x22aee2f` / `0x22af00f`。
- 公钥替换会持久写入游戏外部目录的 `global-metadata.dat`；仅在 LSPosed 中禁用模块不会自动恢复文件。请使用游戏内“关闭 RSA”让模块先恢复；若模块已经禁用，则先启动并授权 Shizuku，在模块主页选择覆盖官方公钥块，或删除 `global-metadata.dat` 让游戏下次启动从原 APK 重新解包。
- 若页面一直提示“Shizuku 未运行”，请确认安装的是 v1.7 或更高版本；旧版 APK 未打包 `ShizukuProvider`，无法初始化 Binder。
- 包名从旧测试版 `cn.mingluan.lyskps` 改为 `com.axuan.lyskps`，旧版配置不会自动迁移。

## 安全问题

请勿在公开 Issue 中提交账号、Token、Cookie、私钥或游戏数据。安全问题请参阅
[SECURITY.md](SECURITY.md)。

## 许可证

项目代码使用 [MIT License](LICENSE)。第三方组件遵循各自许可证。
