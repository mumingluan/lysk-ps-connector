# Security Policy

## Reporting

请通过 GitHub Security Advisories 的私密报告入口提交安全问题。不要在公开 Issue 中附带：

- CA/Leaf 私钥或应用私有目录；
- 游戏账号、Token、Cookie、短信或实名信息；
- 含敏感请求正文的日志和抓包。

报告应包含受影响版本、复现步骤、影响范围以及经过脱敏的日志。

## Scope

重点关注证书/私钥泄露、VPN 分流绕过、非目标应用流量误接管、任意文件访问和 native 崩溃。
客户端版本更新造成的 RSA 偏移变化通常不属于安全漏洞。
