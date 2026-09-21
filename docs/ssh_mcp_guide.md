# CodeBuddy 远程控制 Termux：从免密 SSH 到 MCP 配置指南

> 目标：让 CodeBuddy 通过 SSH 直接控制 Termux（Android 终端环境）上的服务器，完成服务安装、启动、停止、卸载等操作，无需在 Termux 上安装任何 IDE 服务端。
>
> 适用环境：Windows / Linux 客户端 + Termux（Android）远程主机，SSH 端口 8022。

---

## 目录

1. [整体流程](#整体流程)
2. [第一步：生成 SSH 密钥（免密登录基础）](#第一步生成-ssh-密钥免密登录基础)
3. [第二步：配置 SSH config](#第二步配置-ssh-config)
4. [第三步：修复 config 文件权限](#第三步修复-config-文件权限)
5. [第四步：上传公钥到服务器](#第四步上传公钥到服务器)
6. [第五步：验证免密登录](#第五步验证免密登录)
7. [第六步：Termux 服务器环境适配](#第六步termux-服务器环境适配)
8. [第七步：配置 CodeBuddy SSH MCP](#第七步配置-codebuddy-ssh-mcp)
9. [使用示例](#使用示例)
10. [常见问题排查（FAQ）](#常见问题排查faq)
11. [附：其他远程方式对比](#附其他远程方式对比)

---

## 整体流程

```
生成密钥 → 配置 SSH → 上传公钥 → 验证免密 → 适配 Termux → 配置 MCP → 使用
```

所有步骤加起来约 10 分钟，配置一次后长期有效。

---

## 第一步：生成 SSH 密钥（免密登录基础）

### 1.1 检查是否已有密钥

```powershell
# Windows PowerShell
if (Test-Path "$env:USERPROFILE\.ssh") { Get-ChildItem "$env:USERPROFILE\.ssh" | Select-Object Name } else { Write-Output "NO_SSH_DIR" }
```

### 1.2 生成 ed25519 密钥（无 passphrase）

```powershell
ssh-keygen --% -t ed25519 -N "" -f C:\Users\liguo\.ssh\id_ed25519
```

> **重要坑点**：在 PowerShell 中执行 `ssh-keygen -t ed25519 -N ""` 时，PowerShell 会吞掉空字符串参数，导致 `ssh-keygen` 误进入交互流程，**给私钥设置了 passphrase**（表现为连接时提示 `Enter passphrase for key '...'`）。
>
> 解决：使用 `--%` 停止 PowerShell 参数解析，或改用 `cmd /c "ssh-keygen -t ed25519 -N "" -f %USERPROFILE%\.ssh\id_ed25519"`。

### 1.3 验证私钥无 passphrase

```powershell
ssh-keygen -y -f "$env:USERPROFILE\.ssh\id_ed25519"
```

- 能直接输出公钥、无任何密码提示 → 成功
- 若提示 `Enter passphrase` → 需删除重新生成

---

## 第二步：配置 SSH config

编辑 `C:\Users\liguo\.ssh\config`（Windows）/ `~/.ssh/config`（Linux）：

```ssh
Host 192.168.1.18
	HostName 192.168.1.18
	Port 8022
	User root
	IdentityFile ~/.ssh/id_ed25519
```

| 字段 | 说明 |
|---|---|
| `Host` | 连接别名（可自定义） |
| `HostName` | 服务器 IP 或域名 |
| `Port` | SSH 端口（Termux 常见非 22 端口，这里 8022） |
| `User` | 登录账号 |
| `IdentityFile` | 私钥路径 |

> **注意**：`Port` 不能写在 `HostName` 里（如 `HostName 192.168.1.18 -p 8022` 是错误写法），`User` 也不能写成 `ssh root`。

---

## 第三步：修复 config 文件权限

OpenSSH 对 `~/.ssh` 及 `config` 文件权限要求严格，存在 `Everyone` 等多余权限时 SSH 会直接拒绝读取。

```powershell
# 1. 移除继承权限，仅保留当前用户
$u = whoami
icacls "$env:USERPROFILE\.ssh\config" /inheritance:r /grant:r "${u}:(F)"

# 2. 移除显式 Everyone 权限（若存在）
icacls "$env:USERPROFILE\.ssh\config" /remove "Everyone"

# 3. 查看结果验证
icacls "$env:USERPROFILE\.ssh\config"
```

正确结果应只包含：当前用户、SYSTEM、Administrators。

---

## 第四步：上传公钥到服务器

Windows 没有 `ssh-copy-id`，用管道方式手动追加（**需交互输入一次服务器密码**）：

```powershell
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh -p 8022 root@192.168.1.18 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

Linux 则直接用：

```bash
ssh-copy-id -p 8022 root@192.168.1.18
```

> 该命令会把公钥追加到服务器的 `~/.ssh/authorized_keys`，并设置正确权限。

---

## 第五步：验证免密登录

```powershell
ssh 192.168.1.18
```

- 不提示输密码、直接进入 → 免密配置成功
- 仍提示密码 → 检查公钥是否上传成功、服务器 `~/.ssh/authorized_keys` 权限是否为 600

---

## 第六步：Termux 服务器环境适配

### 6.1 典型故障：`Server installation: Failed parsing install script output`

**根因**（Termux 环境特有）：
1. **`.bashrc` 中有大量会执行并产生输出的命令**（如 `sshd`、`termux-wake-lock` 等），VSCode Server / MCP 安装脚本以非交互 shell 方式运行时，这些输出会污染安装脚本的 stdout，导致解析失败。
2. **缺少 `/tmp` 目录**（Termux 默认无该目录），安装脚本强依赖临时目录。

### 6.2 修复步骤

```bash
# ① 备份原 .bashrc
cp ~/.bashrc ~/.bashrc.bak

# ② 在 .bashrc 第一行加"非交互 shell 直接跳过"保护
sed -i "1i case \$- in *i*) ;; *) return;; esac" ~/.bashrc

# ③ 创建临时目录（对应 .bashrc 中设置的 TMPDIR）
mkdir -p ~/tmp
```

`case $- in *i*) ;; *) return;; esac` 的作用：**只有交互式 shell（`-i`）才加载 .bashrc，非交互 shell（安装脚本的运行方式）直接跳过**，从根源上杜绝输出污染。

验证：

```bash
head -3 ~/.bashrc   # 第一行应为 case 保护语句
ls -ld ~/tmp        # 应存在
```

---

## 第七步：配置 CodeBuddy SSH MCP

### 7.1 MCP 配置文件位置

CodeBuddy 支持两个层级（都叫 `mcp.json`）：

| 层级 | 路径 | 生效范围 |
|---|---|---|
| 用户级 | `C:\Users\liguo\.codebuddy\mcp.json` | 所有项目 |
| 项目级 | `<项目根目录>\.codebuddy\mcp.json` | 仅当前项目 |

### 7.2 配置内容

创建/编辑用户级 `C:\Users\liguo\.codebuddy\mcp.json`：

```json
{
  "mcpServers": {
    "termux": {
      "command": "npx",
      "args": ["-y", "ssh-mcp-server"],
      "env": {
        "SSH_HOST": "192.168.1.18",
        "SSH_PORT": "8022",
        "SSH_USER": "root",
        "SSH_KEY": "C:\\Users\\liguo\\.ssh\\id_ed25519"
      }
    }
  }
}
```

### 7.3 环境变量说明

| 变量 | 说明 |
|---|---|
| `SSH_HOST` | 服务器 IP / 域名 |
| `SSH_PORT` | SSH 端口 |
| `SSH_USER` | 登录账号 |
| `SSH_KEY` | 私钥绝对路径（已配免密，无需 `SSH_PASSWORD`） |

> 保存后**重启 CodeBuddy** 生效。配置成功后 MCP 服务器列表会出现 `termux`，状态为已连接。

---

## 使用示例

配置完成后，直接对 CodeBuddy 说自然语言即可：

| 需求 | 对 CodeBuddy 说 |
|---|---|
| 安装服务 | "在 Termux 上安装至简网格服务器" |
| 查看状态 | "查看 Termux 上服务器的运行状态" |
| 启动服务 | "启动至简网格服务器" |
| 停止服务 | "停止至简网格服务器" |
| 卸载服务 | "卸载至简网格服务器" |

AI 会通过 SSH MCP 自动在 Termux 上执行对应的 shell 命令并反馈结果。

---

## 常见问题排查（FAQ）

### Q1：连接提示 `Enter passphrase for key`

私钥被设置了 passphrase（多半是 PowerShell 生成时参数被吞）。重新生成：

```powershell
Remove-Item "$env:USERPROFILE\.ssh\id_ed25519", "$env:USERPROFILE\.ssh\id_ed25519.pub" -Force
ssh-keygen --% -t ed25519 -N "" -f C:\Users\liguo\.ssh\id_ed25519
```

### Q2：`Bad permissions` / SSH 拒绝使用 config

config 权限不符合要求，按[第三步](#第三步修复-config-文件权限)执行 `icacls` 修复。

### Q3：`Server installation: Failed parsing install script output`

Termux 环境问题，按[第六步](#第六步termux-服务器环境适配)修复 `.bashrc` 与 `/tmp`。若仍有残留缓存，清理后重试：

```powershell
ssh -p 8022 root@192.168.1.18 "rm -rf ~/.vscode-server ~/.vscode-server-insiders"
```

### Q4：MCP 连接失败 / 无响应

1. 确认免密 SSH 可用（`ssh 192.168.1.18` 能直接进入）
2. 确认 `npx` 可用且能联网下载 ssh-mcp-server
3. 确认 `SSH_KEY` 路径正确（JSON 中反斜杠需写成 `\\`）
4. 重启 CodeBuddy

---

## 附：其他远程方式对比

| 方式 | 原理 | 是否执行命令 | 配置量 | 适用场景 |
|---|---|---|---|---|
| **SSH MCP（本指南）** | AI 通过 SSH 执行远程命令 | ✅ | 最少 | 控制 Termux 装/卸服务 |
| Remote-SSH | 远程装 IDE 服务端 | ✅ | 较多 | 远程开发（Termux 支持差） |
| CodeBuddy Remote Control | 浏览器访问本机会话 | 本机执行 | 无 | 手机远程操控电脑 CodeBuddy |
| code-server | Termux 上跑 Web IDE | ✅ | 较多 | 以手机为开发机 |
| Syncthing | 目录双向同步 | ❌ | 中 | 纯文件同步 |
| SSHFS/SFTP 挂载 | 远程目录挂载成本地 | ❌ | 中 | 本地编辑远程文件 |

**结论**：若只需"CodeBuddy 控制 Termux 执行安装/卸载等命令"，SSH MCP 是安装配置最少、最贴合需求的方案。
