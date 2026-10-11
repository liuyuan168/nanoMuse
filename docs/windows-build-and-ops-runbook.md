# Windows 构建与运维手册（Runbook）

> 目标：换一台 Windows 电脑也能从零完成 nanoMuse Android 的构建、装机、
> 手机端环境恢复与 MCP 全链路验收。本文记录 2026-10-11 统一 AI 入口项目中
> 踩过的全部坑与最终解法，命令均为 Git Bash 实测。
>
> 配套：`docs/plans/2026-10-11-unified-ai-entry-mcp-plan.md`（架构方案）、
> `docs/plans/2026-10-11-setup-and-mcp-registration.md`（装机/MCP 注册指引）。

---

## 1. 完整构建顺序（缺一步就有对应运行时故障）

```
git clone（含 submodule init） 或 已有仓库
  ├─ ① git submodule update --init android/deps/ish android/deps/proot
  ├─ ② bash android/scripts/prepare_android_sandbox.sh     # rootfs + proot 资产
  ├─ ③ bash android/deps/build_proot.sh                    # 静态 proot（见 §4）
  ├─ ④ bash android/deps/build_rclone_android.sh           # rclone.aar（见 §5）
  │     └─ cp deps/build/rclone/rclone.aar src/android/app/libs/
  └─ ⑤ cd src/android && ./gradlew assembleDebug
        APK: app/build/outputs/apk/debug/app-debug.apk
```

**为什么顺序是死的**——缺任何一件的运行时症状（全部实测踩过）：

| 缺失 | 症状 |
|---|---|
| ② alpine-minirootfs | 沙箱初始化报 `Error: alpine-minirootfs.tar`；纯 LLM 对话正常，一切走沙箱的工具全挂 |
| ③ libproot.so（静态 talloc 版） | `proot 已启动就退出`；手机上直接跑报 `CANNOT LINK ... libtalloc.so.2 not found` |
| ③ libproot-loader.so | 最阴险：proot 启动正常、`native_offload` 正常，但沙箱内**每条命令**返回 `[Shell not running] (exit code: -1)`（Android 10+ W^X 禁止执行 app 数据目录里的文件，必须用 jniLibs 里的 loader） |
| ④ rclone.aar | gradle 在 `checkDebugAarMetadata` 直接失败 |
| gradle 修复（§6） | 见各条 |

**装机后自检**（APK 内容核对）：

```bash
unzip -l app-debug.apk | grep -E "alpine-minirootfs|proot-aarch64|phone-ops|libproot"
# 应看到: assets/alpine-minirootfs.tar(.gz)  assets/proot-aarch64
#         lib/arm64-v8a/libproot.so  libproot-loader.so  libproot-loader32.so
#         assets/default_mount/usr/local/bin/phone-ops-mcp + lib/phone-ops-mcp/server.py
```

## 2. PC 环境清单（一次性）

- JDK 21：`D:\Programs\Android\jdk-21`（**勿用** `C:\Program Files\Common Files\Oracle\Java\javapath` 的 javac——本机这个 shim 本身 segfault）
- Android SDK `D:\Programs\Android\androidsdk` + NDK（≥27.2；构建 proot 的脚本建议 r28+，r27.2 实测可用）
- `src/android/local.properties`：`sdk.dir=D\:\\Programs\\Android\\androidsdk`
- Go 1.26+，`GOPROXY=https://goproxy.cn,direct`（proxy.golang.org 被墙）
- gomobile + gobind：`go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest`，然后 `gomobile init`
- Git Bash 的 PATH 注意：**临时 export `MSYS_NO_PATHCONV=1` 只用于 adb push**——它会把 MSYS→Windows 的 argv 路径转换全关掉，编译类命令（clang 等）带了它会收到 `/e/...` 裸路径直接报 no such file（实测踩过）

## 3. submodule 注意

上游 submodule：`android/deps/proot`（nano-muse/proot @4002f4bd）、`android/deps/ish`。
国内镜像拿不到 pin 的 4002f4bd 时，可用 OpenMinis/proot@8cf13e9（含 native_offload
扩展，构建实测可用）；此时 `git status` 会显示 submodule 指针偏差，属已知偏差，
**不要提交**该指针改动，文档记录即可。

## 4. build_proot.sh 的 Windows 适配

上游脚本只认 darwin/linux host。已在本仓库 patch（`MINGW64_NT-*|MSYS_NT-*` 分支）。
此外需要：

```bash
# ① 工具 shim（NDK 的 per-target clang 在 Windows 是 .cmd，bash 调不了；
#    llvm-ar 等是 .exe，脚本里无后缀引用 test -x 会失败）
mkdir -p /tmp/ndkshim && cd /tmp/ndkshim
NDKBIN="D:/Programs/Android/androidsdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin"
printf '#!/bin/sh\nexec "%s/clang.exe" --target=aarch64-linux-android26 "$@"\n' "$NDKBIN" > aarch64-linux-android26-clang
for t in llvm-ar llvm-strip llvm-objcopy llvm-objdump llvm-ranlib; do
  printf '#!/bin/sh\nexec "%s/%s.exe" "$@"\n' "$NDKBIN" "$t" > "$t"
done
chmod +x *

# ② 跑（NDK 自带 make.exe；路径一律正斜杠，反斜杠会被 bash 吃成转义）
export ANDROID_NDK_HOME='D:/Programs/Android/androidsdk/ndk/27.2.12479018'
export PATH="/tmp/ndkshim:$NDK_HOME/prebuilt/windows-x86_64/bin:$PATH"
bash android/deps/build_proot.sh
```

已知两个小坑：
- `build.h` 由 make 的 feature 检测生成，并行下可能竞态缺失 → 手工生成后重跑 make：
  `build.h` 内容 = VERSION（fork commit 短 hash）+ `HAVE_PROCESS_VM` + `HAVE_SECCOMP_FILTER`
- loaders 是 git 里的 Termux 构建版（sha256 pin），**绝不能用 fork 自编译的 loader 替换**
  ——脚本会校验并报错，照提示 `git checkout --` 恢复即可

## 5. rclone.aar（不入库，每次新环境要构建）

```bash
export GOPROXY=https://goproxy.cn,direct
export ANDROID_HOME='D:/Programs/Android/androidsdk'        # 必须写法：gomobile.exe 只认这个
export ANDROID_NDK_HOME='D:/Programs/Android/androidsdk/ndk/27.2.12479018'
export JAVA_HOME='D:/Programs/Android/jdk-21'
export PATH="/d/Programs/Android/jdk-21/bin:/c/Users/<user>/go/bin:$PATH"
bash android/deps/build_rclone_android.sh                    # 产出 deps/build/rclone/rclone.aar
mkdir -p android/src/android/app/libs && cp android/deps/build/rclone/rclone.aar android/src/android/app/libs/
```

## 6. gradle 构建三关（已修入仓库，列出供诊断）

1. **WSL bash 抢占**：Exec task 裸 `bash` 解析到 `C:\Windows\System32\bash.exe`（未装
   WSL 发行版必失败）→ `stageDebugSkillAssets` 已改为探测 Git Bash 绝对路径
   （`C:/Program Files/Git/bin/bash.exe` 或 `D:/Programs/Git/bin/bash.exe`）
2. **Kotlin daemon 崩溃**（连接 reset）：`gradle.properties` 已加
   `kotlin.daemon.jvmargs=-Xmx4g` + `org.gradle.workers.max=4`
3. **CPU 敏感**：构建进程务必降优先级跑（BelowNormal），把 CPU 留给常驻服务

## 7. 装机与首次初始化

```bash
adb install -r app-debug.apk
```

- 原地升级不变 applicationId（`io.github.nanomuse.app`），用户数据保留
- **重装/升级不清沙箱数据**（files/alpine-rootfs/ 保留）；若沙箱状态可疑可
  「设置 → Rootfs 设置」重置，或 `run-as io.github.nanomuse.app rm -rf files/alpine-rootfs`
- 沙箱内第一次跑 `minis-mcp-cli`/`phone-ops-mcp` 需要 `apk add python3`：
  **必须有可用国际网络**（rootfs 的 /etc/apk/repositories 出厂即阿里云镜像，
  但实际卡住时是手机网络到镜像慢；实测手机开 TUN 代理后立刻通）。
  诊断：`ps -A | grep proot` 看到 `sh → sh → apk` 进程树即卡在此处；
  可 `run-as io.github.nanomuse.app kill <apk_pid>` 后开代理重试。

## 8. 手机重启后的恢复流程（每次重启都要做）

```bash
# ① Shizuku server（v13+ 直接执行 libshizuku.so，官方指令；勿用 app_process——入口类不在 manager dex 里）
adb shell am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity  # 解除 stopped 状态
adb shell nohup /data/app/~~*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so &
#    app 内「无线调试启动」若报 TLS CERTIFICATE_UNKNOWN，就是它自己的 adb TLS 会话失配，走 PC 拉起绕过
#    首次：Shizuku app 需联网下载（TUN 代理）；重启后 nanoMuse 会重新弹 Shizuku 授权框，点允许

# ② 无障碍（重启会被系统清掉）
adb shell settings put secure enabled_accessibility_services io.github.nanomuse.app/com.openminis.app.accessibility.MinisAccessibilityService
adb shell settings put secure accessibility_enabled 1
#    勿拼接保留 MIUI 原条目（可能无类名导致整串格式坏掉）；验证：
adb shell dumpsys accessibility   # 看 Bound services 里有 nanoMuse

# ③ 两个本地 MCP 前台服务（token 持久化，无需重设；启动检查在 onResume）
adb shell am start -n com.larry.todo/.MainActivity        # 播 8765
adb shell am start -n com.smail.pagevault/.MainActivity   # 播 8766
adb shell "netstat -tln | grep -E '8765|8766'"            # 两个 LISTEN 即成

# ④ pagevault MCP 的开关（改 token / 重开）——注意澎湃OS 拦隐式广播，必须显式 -n
adb shell am broadcast -n com.smail.pagevault/.McpControlReceiver -a com.smail.pagevault.mcp.ENABLE --es token <TOKEN>
adb shell am broadcast -n com.smail.pagevault/.McpControlReceiver -a com.smail.pagevault.mcp.DISABLE
```

**nanoMuse app 内还有一道自己的权限闸**（与系统权限无关）：
设置 → 权限 → Integrations，允许 Agent 使用 `android-shizuku-cli` /
`android-a11y-cli`。phone_info 报「设备信息被权限设置挡住」即指这里。

## 9. USB / adb 故障（今天出现过的两种形态）

- `adb devices` 显示 `offline`：`adb kill-server && adb start-server` →
  `adb reconnect offline` → 都不行就重启手机
- 显示 `device` 但 `adb shell echo` 卡死（半死通道）：adb install 会无限等待；
  重插线/换口。已三次复现，**这根 USB 线物理接触不良**，建议换线
- 备选：无线调试 `adb connect <ip>:<port>` 彻底绕开 USB

## 10. xinzhi 服务端部署（腾讯云 111.231.171.174）

```bash
cd D:/work/xinzhi && bash deploy/build.sh          # 本地交叉编译 + web dist
ssh-key 里：/d/work/cloud-infra/larry.pem，用户 ubuntu
# 【坑】scp -r 目标目录已存在时会嵌套成 bundle/bundle/！先删旧目录：
ssh -i larry.pem ubuntu@111.231.171.174 'rm -rf /tmp/xinzhi-bundle'
scp -i larry.pem -r deploy/bundle ubuntu@111.231.171.174:/tmp/xinzhi-bundle
ssh -i larry.pem ubuntu@111.231.171.174 'sudo bash /tmp/xinzhi-bundle/deploy.sh'
# 验证版本 = 本地 commit：
curl -sk https://111.231.171.174:7082/api/version
# MCP 端点自检（token 在服务器 /opt/xinzhi/.env 的 XINZHI_API_TOKEN）：
#   无 token → 401；initialize → xinzhi-mcp 0.2.0；GET → 405；tools/call 真实检索
```

## 11. MCP 端点契约（四端一致，改协议时对齐）

无状态单 POST、application/json 响应（Streamable HTTP 2025-03-26 简化形态）：
initialize（回显客户端 protocolVersion）→ 通知回 202 空体 → GET 显式 405 →
未知方法 -32601 / 未知工具 -32602 → 工具异常回 `isError:true` 的 result（会话不中断）。
鉴权：`Authorization: Bearer` 或 `?token=`。实现位置：
`xinzhi/internal/api/handlers_mcp.go`、`larry-todo android mcp/TodoMcpServer.kt`、
`pagevault mobile McpServer.java`、`nanoMuse assets .../phone-ops-mcp/server.py`。
