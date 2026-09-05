# LYSK-PS-Connector

《恋与深空》国服客户端的 Shizuku RSA 公钥替换与 Android `VpnService` 域名分流工具。

> 本项目仅用于个人研究、协议调试和自建服务测试，与叠纸游戏及其关联公司无关。
> 使用者应自行确认当地法律、服务条款和账号风险。

包名：`com.axuan.lyskps`  
默认分流应用：`com.papegames.lysk.cn`、`com.papegames.lysk.tw`、`com.papegames.lysk.jp`、`com.papegames.lysk.en`、`com.papegames.lysk.kr`

最低系统：Android 7.0（API 24）

## 功能

- 通过 Shizuku 立即补丁 `global-metadata.dat` 中的私服 RSA 公钥，客户端独立完成文件操作。
- RSA 首次修补前自动备份两段原公钥到应用私有目录；可严格从备份还原、覆盖官方公钥，或删除 `files/il2cpp` 重建。
- 选择 Pape-ResSolver 输出的 NLS ZIP 后，自动校验并提取同名 NX，备份游戏原 ZIP/NX 后成对替换。
- NLS 可从应用私有备份成对还原，或删除已安装的 ZIP/NX 让游戏重新获取官方资源。
- 提供独立的“启动恋与深空”按钮。
- 集成 `ShizukuProvider`；首次执行 RSA 文件操作前，请启动 Shizuku 并授权本客户端。
- 替换用 RSA 公钥保存在客户端配置中；扫描 metadata 中唯一的一组相邻 480/243 字节 RSA XML 块定位，版本变化无需手动填写偏移。文件操作由 Shizuku UserService 完成。
- 只接管指定应用的 `VpnService`；包名可填 `*`，表示除 LYSK-PS-Connector 自身外的全部应用。
- 域名规则支持普通域名、`re:` 正则表达式和 `!` 直连排除；排除规则始终优先。
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
2. 启动 Shizuku；如需 RSA 文件操作，请在首次提示时授权 `LYSK-PS-Connector`。
3. 打开 `LYSK-PS-Connector`：
   - 点击“补丁 RSA（Shizuku）”，授权后立即改写现有 metadata；
   - 需要还原 RSA 时，优先选择修补前自动备份，也可覆盖官方公钥或删除 `files/il2cpp`；
   - 安装 NLS 时只需选择 Solver 输出的数字 ZIP，NX 会从 ZIP 自动提取；
   - 还原 NLS 时选择私有备份还原，或删除对应 ZIP/NX；
   - 配置过滤域名和 VPN 作用包名；
   - 选择 HTTP 代理或 Web 重定向；
   - 分别填写“上游代理地址”和“上游 HTTP 服务地址”；
   - 按需启用内置 HTTPS 包装器。
4. 点击“保存并启动”，首次使用时接受 Android VPN 授权。

### 域名规则

- `papegames.com`：命中该域名及全部子域名。
- `re:^api\d+\.papegames\.com$`：使用正则表达式匹配完整主机名，忽略大小写。
- `!hotupdate.papegames.com`：排除该域名及子域名，强制走系统直连。
- `!re:^.+\.hotupdate\.papegames\.com$`：使用正则表达式排除。

排除规则与包含规则同时命中时，结果为 `DIRECT-EXCLUDED`。
内置列表包含 `papegames.com`、`papegames.cn` 和 `infoldgames.com`，资源下载排除规则为：

```text
!re:^x3[a-z]+-client-[a-z0-9-]+\.(?:papegames|infoldgames)\.com$
```

地区代码使用正则通配，主 CDN 与 `-backup` 资源域名均直连，登录、风控和 Gate 域名继续分流。升级时自动扩展旧版默认域名及单国服应用列表；自定义列表（包括 `*`）保持用户设置。首页选择已安装的目标客户端后，RSA/NLS 文件操作、备份与启动按钮均使用该客户端目录。选择目标独立于 VPN 应用分流列表；文件操作开始时锁定目标。

### HTTPS 包装器证书

启用包装器后，首次启动会在应用私有目录随机生成 CA pair 和默认 Leaf pair。默认 Leaf SAN 包含 `papegames.com`、`*.papegames.com`、`infoldgames.com`、`*.infoldgames.com`；已有自动生成的 Leaf 在加载时更新并沿用原 CA，手动导入的固定证书保持不变。动态叶证书按实际目标主机签发。可在页面中：

- 分别导入 CA 证书/私钥、Leaf 证书/私钥；只有配对校验成功才会启用；
- 导出 `LYSK-PS-Connector-CA.crt`；
- 导出 Android 系统 CA 目录使用的 `xxxxxxxx.0` PEM；
- 主动重新随机生成 CA/Leaf。

新版 Android 请先导出 `.crt`，再前往系统“设置 → 安全/密码与安全 → 更多安全设置 →
加密与凭据 → 安装证书 → CA 证书”选择文件。不同 OEM 的菜单名称可能不同。

root 用户亦可将证书 `.0` 文件添加到系统 CA + Conscrypt APEX 中，但游戏默认信任用户证书，
因此不必这么做。

> CA 私钥位于应用私有目录。请只在受控设备上使用，不要公开分享导出的私钥或完整应用数据。

## 构建

要求：JDK 17/21、Android SDK Platform 34。项目已包含 Gradle Wrapper，以及
`arm64-v8a`、`x86_64` 的 `libtun2proxy.so`。

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
app/build/outputs/apk/release/LYSK-PS-Connector-release.apk
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
- 公钥替换会持久写入游戏外部目录的 `global-metadata.dat`。需要还原时，请启动并授权 Shizuku，在客户端页面选择覆盖官方公钥块，或删除 `files/il2cpp` 目录让游戏下次启动自动重建。
- 若页面一直提示“Shizuku 未连接”，请确认 Shizuku 正在运行，并在 Shizuku 管理器中重新检查授权。
- 包名从旧测试版 `cn.mingluan.lyskps` 改为 `com.axuan.lyskps`，旧版配置不会自动迁移。

## 安全问题

请勿在公开 Issue 中提交账号、Token、Cookie、私钥或游戏数据。安全问题请参阅
[SECURITY.md](SECURITY.md)。

## 许可证

项目代码使用 [MIT License](LICENSE)。第三方组件遵循各自许可证。

## 多客户端 RSA 与 Solver NLS

- 目标支持国服、台服、日服、英服和韩服，只列出本机已安装客户端。
- RSA 不再使用国服固定偏移。缺少或存在多组候选公钥时拒绝写入；补丁后回读两块内容。
- 官方公钥从所选客户端已安装 APK 的 metadata 提取，不将国服内置原公钥写入外服。仅公钥恢复要求 APK 与现有 metadata 的非公钥内容指纹一致。
- RSA 备份按客户端隔离，并保存 metadata 去除公钥区域后的 SHA-256；恢复时核对版本。未打补丁的新版本会归档旧备份后重新备份，旧版无指纹备份不会直接覆盖当前 metadata。
- NLS 使用 Pape-ResSolver `client-patch` 输出的数字 ZIP：method-14/XZ 压缩，同名 NX，原始长度和 CRC 校验。安装位置分别是当前客户端的 `files/XFileZip` 和 `files/XPackage`。
- 安装和备份恢复要求当前 NX 与补丁/备份长度相同，变化限制在一个最多 128 字节的区间内，适配 Solver 的单个等长 AppKey 替换。其他版本/区域资源不可凭相同包名安装；需从对应资源重新生成。
- NLS 备份与安装记录按客户端隔离；国服继续使用已有备份位置。不会将某服补丁自动转换为其他服补丁。

离线验证的四外服 6.0.0 APK 均包含唯一相邻 RSA 块，起点为 36367919 与 36368399；这些值仅用于验证扫描结果，不作为运行时偏移。测试涵盖扫描缓冲区边界、歧义拒绝、指纹稳定性、NLS 兼容性以及分流规则。实际 Shizuku 跨客户端写入仍需设备验证。
