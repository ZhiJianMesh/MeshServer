# =============================================================================
# mesh1.ps1  --  mesh.ps1 的改进版（与 sbin/mesh1.sh 保持同一套改进）
#
# 作者: liguoyong
# 日期: 2023/3/31  (由 sbin/mesh.sh 转换为 PowerShell 版本并改进)
#
# 用法:
#   powershell -NoProfile -ExecutionPolicy Bypass -File mesh1.ps1 start|stop|restart|status [om_pwd [main_bios]]
#
# 与 mesh.ps1 相比新增/改进的问题（与 mesh1.sh 对应）：
#   1. Get-MeshPids 按 "-Dname=mesh_server" 精确匹配，
#      避免误匹配其它含同名字符串的进程（原为 Contains($AppName) 宽泛匹配）
#   2. 健康检查解析 JSON 的 code 字段（ConvertFrom-Json），比仅判断花括号更可靠；
#      响应结果缓存在 $Script:CheckResp，status 不再重复请求
#   3. 增加 java 命令可用性检查
#   4. 等待/重启超时等魔法数字提取为顶部变量（$StartWaitSec/$StopWaitSec/$RestartSleepSec）
#   5. status 的 CPU 占用改为两次采样计算瞬时利用率，更接近 top 的 %CPU
#   6. 启动 java 时过滤空参数（om_pwd / main_bios 未提供时不传空串）
#   7. 返回值语义统一：Test-MeshExist 0=进程存在/1=不存在；
#      Test-MeshStarted 0=正常/1=未启动/2=吊死
#
# 特别注意：
#   该脚本使用 Stop-Process 强制终止指定的 java 程序进程。
#   所以在杀死进程前，可能会造成数据丢失或数据不完整。
#   Windows 上没有与 Linux kill -SIGINT / kill -SIGTERM 等价的机制，
#   无法让 JVM 执行优雅关闭(shutdown hook)，只能强制终止。
#   如果必须要考虑到这类情况，则需要改写此脚本。
# =============================================================================

param(
    [string]$Command = '',
    [string]$OMPWD = '',
    [string]$MAINBIOS = ''
)

# ---- 全局变量 ----
$AppName = 'mesh_server'                                  # JAVA 应用程序的名称
$ShellName = Split-Path -Leaf $MyInvocation.MyCommand.Path
$JavaMain = 'cn.net.zhijian.platform.ServerMain'
$MeshHome = Split-Path -Parent $PSScriptRoot                  # 脚本位于 sbin 下，HOME 为 server 根目录
$PidFile = Join-Path $MeshHome '.pid'                         # 存放进程 PID 的文件，同时表示是否继续运行
$Logs = Join-Path $MeshHome 'logs'
$LibPath = Join-Path $MeshHome 'libs'
$CheckUrl = 'http://localhost:8523/backend/api/checkup'       # 健康检查接口地址

# 等待/超时参数（秒）
$StartWaitSec = 30      # 等待服务启动完成的最长时间
$StopWaitSec = 30       # 等待服务停止完成的最长时间
$RestartSleepSec = 3    # 重启前等待时间

# 将 bin/main（主类）、libs 目录与 sbin/dependency 目录下的所有 jar 加入 classpath（Windows 下以分号分隔）
$CpItems = @()
$CpItems += Join-Path $MeshHome 'bin\main'
Get-ChildItem -Path (Join-Path $LibPath '*.jar') -ErrorAction SilentlyContinue |
    ForEach-Object { $CpItems += $_.FullName }
Get-ChildItem -Path (Join-Path $MeshHome 'sbin\dependency\*.jar') -ErrorAction SilentlyContinue |
    ForEach-Object { $CpItems += $_.FullName }
$Cp = $CpItems -join ';'

# java 虚拟机启动参数
$JavaOpts = @(
    '-Xms512m',
    '-Xmx512m',
    '-XX:+UseSerialGC',
    '-Dfile.encoding=utf-8',
    '-XX:MaxMetaspaceSize=1024m',
    '-XX:ParallelGCThreads=1',
    '-XX:+HeapDumpOnOutOfMemoryError',
    "-XX:HeapDumpPath=$(Join-Path $Logs 'HeapDump.hprof')",
    # netty 需要的配置，java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent
    '--add-opens', 'java.base/jdk.internal.misc=ALL-UNNAMED',
    # netty 提示 Reflective setAccessible(true) disabled
    '-Dio.netty.tryReflectionSetAccessible=true',
    # netty 提示 java.nio.DirectByteBuffer.<init>(long, {int,long}): unavailable
    '--add-opens', 'java.base/java.nio=ALL-UNNAMED',
    # 解决日志配置文件加载问题
    "-Dlogs.root=$Logs",
    "-Dlogback.configurationFile=$(Join-Path $MeshHome 'conf\logback.xml')",
    "-Dname=$AppName",
    '-classpath',
    $Cp
)

# ---- 工具函数 ----

# 查询 mesh_server 的 java 进程 PID 列表（按 -Dname=mesh_server 精确匹配，等价 ps -ef | grep -Dname=mesh_server）
function Get-MeshPids {
    $match = "-Dname=$AppName"
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($match) } |
        ForEach-Object { $_.ProcessId }
}

# 检查程序是否处于运行状态
function Test-MeshExist {
    return @(Get-MeshPids).Count -gt 0
}

# 健康检查响应缓存（Test-MeshStarted 内部填充，供调用方直接使用，避免重复请求）
$Script:CheckResp = ''

# 请求健康检查接口
function Get-CheckupResponse {
    try {
        $Script:CheckResp = (Invoke-WebRequest -Uri $CheckUrl -TimeoutSec 3 -UseBasicParsing).Content
    } catch {
        $Script:CheckResp = ''
    }
    return $Script:CheckResp
}

# 解析 JSON 中的 code 字段；解析失败或字段缺失返回 $null
function Get-JsonCode {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return $null }
    try {
        $obj = $Json | ConvertFrom-Json
        return $obj.code
    } catch {
        return $null
    }
}

# 只有返回 0 时才是成功，1 为未启动，2 为吊死
function Test-MeshStarted {
    if (-not (Test-MeshExist)) { $Script:CheckResp = ''; return 1 }
    $resp = Get-CheckupResponse
    $code = Get-JsonCode -Json $resp
    if ($code -eq 0) { return 0 }
    return 2
}

# 将参数数组拼成命令行字符串（对含空格/引号的参数加引号）
function ConvertTo-CommandLine {
    param([string[]]$ArgumentList)
    $parts = foreach ($a in $ArgumentList) {
        if ($a -match '[\s"]') {
            '"' + ($a -replace '"', '\"') + '"'
        } else {
            $a
        }
    }
    return ($parts -join ' ')
}

# 查询进程 CPU/内存占用（CPU 为两次采样计算的瞬时利用率，近似 top 的 %CPU）
function Get-CpuMem {
    param([int]$TargetPid)
    $p1 = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
    if (-not $p1) { Write-Host 'CPU: n/a MEM: n/a'; return }
    $cores = [Environment]::ProcessorCount
    Start-Sleep -Milliseconds 500
    $p2 = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
    if (-not $p2) { Write-Host 'CPU: n/a MEM: n/a'; return }
    $cpuPct = [math]::Round((($p2.CPU - $p1.CPU) / (0.5 * $cores)) * 100, 2)
    if ($cpuPct -lt 0) { $cpuPct = 0 }
    $totalMem = (Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize * 1KB
    $memPct = [math]::Round(($p2.WorkingSet64 / $totalMem) * 100, 2)
    Write-Host "CPU: $cpuPct% MEM: $memPct%"
}

# ---- 核心功能函数 ----

# 服务启动方法
function Start-Server {
    $st = Test-MeshStarted
    if ($st -eq 0) {
        $pids = @(Get-MeshPids)
        Write-Host "$AppName is already running, pid is $($pids -join ',')"
    } elseif ($st -eq 1) {
        Start-Loop
    } else {
        # 进程吊死：先停止再启动
        Stop-Server
        Start-Loop
    }
}

# 循环启动脚本（后台守护），java 异常退出后自动重启，直到 .pid 文件被删除
function Start-Loop {
    New-Item -ItemType Directory -Force -Path $Logs | Out-Null
    Write-Host '==============================================='
    # 创建（清空）.pid 文件，作为守护循环是否继续的标志
    New-Item -ItemType File -Force -Path $PidFile | Out-Null

    # 后台运行守护循环（隐藏窗口），保证关闭当前终端后服务仍可继续运行
    $psExe = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' }
    $innerArgs = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden',
        '-File', $PSCommandPath, '__loop'
    )
    if ($OMPWD) { $innerArgs += $OMPWD }
    if ($MAINBIOS) { $innerArgs += $MAINBIOS }
    Start-Process -FilePath $psExe -ArgumentList (ConvertTo-CommandLine $innerArgs)
}

# 守护循环主体（由隐藏窗口的后台进程执行）：
# 顺序执行 "启动 java -> 等待健康检查 -> 堵塞等待退出"，
# java 快速崩溃时不会堆积监控进程（等价 mesh1.sh 将 test_server 移出循环）
function Start-InternalLoop {
    while (Test-Path $PidFile) {
        # 组装 java 参数并过滤空参数（om_pwd / main_bios 未提供时不传空串）
        $argList = @($JavaMain)
        foreach ($a in @($OMPWD, $MAINBIOS)) { if ($a) { $argList += $a } }
        $allArgs = @($JavaOpts) + $argList

        $outFile = Join-Path $Logs 'java.out.log'
        $errFile = Join-Path $Logs 'java.err.log'
        $proc = $null
        try {
            # -WorkingDirectory 保证 java 进程的 user.dir 始终指向 server 根目录（sbin 的上一层）。
            # ServerMain 通过 System.getProperty("user.dir") 定位 conf 目录，
            # 从任意路径调用本脚本时若不指定，会导致配置/日志初始化失败。
            $proc = Start-Process -FilePath 'java' `
                -ArgumentList (ConvertTo-CommandLine $allArgs) `
                -WorkingDirectory $MeshHome `
                -RedirectStandardOutput $outFile `
                -RedirectStandardError $errFile `
                -PassThru -WindowStyle Hidden
        } catch {
            Write-Host "start java failed: $_"
            break
        }

        # 等待服务启动完成（每 1 秒检查一次健康状态，最多 $StartWaitSec 秒）
        Test-ServerWait

        # 在此堵塞，直到 java 进程退出
        if ($proc -and -not $proc.HasExited) { $proc.WaitForExit() }

        if (-not (Test-Path $PidFile)) { break }  # .pid 文件不存在，则终止守护循环
    }
}

# 启动检测：等待服务启动成功并输出结果
function Test-ServerWait {
    Write-Host 'Starting'
    $count = 0
    $success = $false
    while ($true) {
        if ((Test-MeshStarted) -eq 0) { $success = $true; break }
        if ($count -gt $StartWaitSec) { break }
        if (-not (Test-Path $PidFile)) { break }
        Start-Sleep -Seconds 1
        Write-Host -NoNewline '.'
        $count++
    }
    Write-Host ''

    if ($success) {
        Start-Sleep -Seconds 1
        $pids = @(Get-MeshPids)
        if ($pids.Count -gt 0) {
            Set-Content -Path $PidFile -Value $pids[0] -Encoding Ascii
            Write-Host "start $AppName successed, pid is $($pids[0])"
            Write-Host $Script:CheckResp
            Write-Host ''
        } else {
            Write-Host "start $AppName failed"
        }
    } else {
        Write-Host "start $AppName failed"
    }
}

# 服务停止方法
function Stop-Server {
    if (Test-MeshExist) {
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue  # 守护循环判断 .pid 不存在则 break
        $pids = @(Get-MeshPids)
        Write-Host -NoNewline "Stopping server $($pids -join ',')"
        foreach ($p in $pids) {
            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
        }

        # 循环判断服务进程是否存在（最多 $StopWaitSec 秒）
        $count = 0
        $stopped = $false
        while ($true) {
            if (-not (Test-MeshExist)) { $stopped = $true; break }
            if ($count -gt $StopWaitSec) { break }
            Start-Sleep -Seconds 1
            Write-Host -NoNewline '.'
            $count++
        }
        Write-Host ''

        if (-not $stopped) {  # 没有正确停止，则强制终止
            Write-Host "kill -SIGINT $($pids -join ',')  (Windows 无 SIGINT，直接强制终止)"
            foreach ($p in @(Get-MeshPids)) {
                Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
            }
        }
    } else {
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    }
    Write-Host "$AppName process stopped!"
}

# 服务运行状态查看方法
function Get-Status {
    $st = Test-MeshStarted
    if ($st -eq 0) {
        $pids = @(Get-MeshPids)
        Write-Host "$AppName is running,pid is $($pids -join ',')"
        Write-Host $Script:CheckResp
        Get-CpuMem -TargetPid $pids[0]
    } elseif ($st -eq 1) {
        Write-Host "$AppName is not running!"
    } else {
        Write-Host "$AppName is hung up!"
    }
}

# 重启服务方法
function Restart-Server {
    Stop-Server
    Write-Host 'Wait a moment'
    Start-Sleep -Seconds $RestartSleepSec
    Start-Server
}

# 帮助说明，用于提示输入参数信息
function Show-Usage {
    Write-Host "Usage: powershell -NoProfile -ExecutionPolicy Bypass -File $ShellName start|stop|restart|status [om_pwd [main_bios]]"
    exit 1
}

# ---- 主逻辑 ----

# 检查必需命令是否存在
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host 'ERROR: required command "java" not found in PATH' -ForegroundColor Red
    exit 1
}

# 内部命令：守护循环进程入口
if ($Command -eq '__loop') {
    Start-InternalLoop
    exit 0
}

switch ($Command) {
    'start' { Start-Server }
    'stop' { Stop-Server }
    'restart' { Restart-Server }
    'status' { Get-Status }
    default { Show-Usage }
}
exit 0
