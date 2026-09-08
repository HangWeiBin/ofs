# 域名转发 (Domain Forward)

一款免 root 的安卓小工具：**让手机访问指定域名时，自动改连到你指定的 IP**（相当于手机端自定义 hosts）。

- 整机生效：所有使用系统 DNS 的 App / 浏览器都会命中规则
- 支持内网 IP（192.168.x.x 等）与公网 IP、IPv6 目标地址
- HTTP / HTTPS 均可（HTTPS 要求目标服务器证书与域名匹配，否则会有证书提示）
- 原理：通过系统 VPN 授权（`VpnService`）只接管发往 DNS 服务器的 UDP 53 流量——命中规则的域名直接应答你填写的 IP；其它域名原样转发给真实 DNS 并回传结果。**其余网络流量完全不经过本 App**，不影响网速与稳定性，切换 WiFi / 流量时自动跟随当前网络的 DNS。

> 局限：无法拦截「私人 DNS（DoT/DoH）」的加密解析。若感觉没生效，请到 系统设置 → 网络与互联网 → 私人 DNS 选择“关闭 / 自动”。

## 应用内使用

1. 打开 App，添加规则，例如：
   - 域名：`myhome.example.com` → IP：`192.168.1.100`
   - 勾选“同时匹配子域名”后，`www.myhome.example.com` 等也会命中
2. 点「开启转发」，首次会弹出系统 VPN 授权框，点“允许”
3. 顶部状态栏出现常驻通知即表示运行中；点「停止转发」或通知上的“停止”即可关闭

> 若开启后域名仍解析到旧地址，可开关一次飞行模式，或重启相关 App（DNS 缓存）。

## 在线打包（不需要在本机安装 Android 环境）

本仓库已配置 GitHub Actions（`.github/workflows/build-apk.yml`），代码一上传即自动在云端打出 APK。任选一种方式把代码传到你的 GitHub 仓库：

### 方式一：网页直接上传（无需安装任何东西）

1. 打开 <https://github.com/new> 新建仓库（名字随意，如 `domain-forward`，公开/私有皆可）
2. 进入仓库页面 → 点 **Add file → Upload files**
3. 把本目录下的**所有文件**（含 `app`、`.github`、`gradle` 等文件夹）拖进去
   - 注意：不要上传 `build/`、`.gradle/` 等本地构建产物（当前目录里也没有）
4. 点 **Commit changes**

### 方式二：本地 git 推送（本机已装 git 时）

```bash
cd DomainForward
git init
git add .
git commit -m "domain forward app"
git branch -M main
git remote add origin https://github.com/<你的用户名>/domain-forward.git
git push -u origin main
```

### 下载 APK

1. 打开仓库页 → **Actions** 标签 → 点击最新的 **Build APK** 工作流
2. 跑完后进入该次运行记录 → 最底部 **Artifacts** 区 → 下载 `app-debug-apk`
3. 解压得到 `app-debug.apk`，传到手机安装（允许“安装未知来源应用”）即可

之后每次改代码 push，或点 Actions 里的 **Run workflow**，都会重新打包。

## 常见问题

- **还是访问到旧地址**：先确认“私人 DNS”处于关闭/自动；再开关一次飞行模式清缓存。
- **内网 IP 连不上**：目标 IP 是内网时，手机必须和目标在同一局域网。
- **路由器/网关管理页打不开**：若你的 DNS 服务器与网关是同一 IP（很常见），开启转发期间访问该 IP 的非 53 端口流量会被忽略，请先停止转发再打开管理页。
- **提示“VPN 已连接”但无效**：请勿同时开启其它 VPN 类 App；本 App 依赖系统 DNS，运营商劫持 DNS 时建议在网络设置里把 DNS 改为 `223.5.5.5` 等公共 DNS 后再开启。
- **想在别处自己重新打包**：需要 JDK 17 与 Gradle 8.7，在仓库根目录执行 `gradle :app:assembleDebug`（首次会自动下载 Android SDK 依赖）。若想用 wrapper，先执行一次 `gradle wrapper --gradle-version 8.7` 生成后再用 `./gradlew :app:assembleDebug`。

## 技术栈

- Kotlin + Android Gradle Plugin 8.5.2，minSdk 26 / targetSdk 34
- 核心：`DomainForwardService`（VpnService）、`DnsPacket`（DNS 解析/应答）、`VpnIp`（IPv4/UDP 封包）
