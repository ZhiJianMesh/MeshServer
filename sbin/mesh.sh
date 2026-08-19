#!/bin/bash
# =============================================================================
# mesh1.sh  --  mesh.sh 的改进版
#
# 作者: flyinmind@csdn.net
# 日期: 2023/3/31  (改进)
#
# 在 mesh.sh 基础上修复/改进的问题：
#   1. 修复 start/status 中 "elif [ $? -eq ... ]" 的 $? 被 if 条件消费导致
#      判断失效的 bug（原版 is_started 返回 2 的"吊死"态不会走 stop 再启动）
#   2. 变量名 HOME 改为 APP_HOME，避免覆盖用户主目录环境变量
#   3. 新增 get_pids() 统一查询进程，按 "-Dname=mesh_server" 精确匹配，
#      避免裸 grep 误匹配其它含同名字符串的进程
#   4. classpath 改用 glob + 存在性判断拼接，去掉裸 ls；
#      显式加入 . 与 bin/main 作为 classpath 起点，不再依赖 shell 隐式的空路径元素
#   5. JAVA_OPTS 改为数组，-classpath 从中拆分出来单独传参，
#      避免 jar 路径含空格时被 shell 分词截断
#   6. 返回值语义统一：is_exist 0=进程存在/1=不存在；
#      is_started 0=正常/1=未启动/2=吊死
#   7. status 的 CPU/MEM 改用 ps -o %cpu,%mem 读取，
#      修正原 top -b 输出解析取错列的问题
#   8. 健康检查解析 JSON 中的 "code":0 字段，比仅判断花括号更可靠；
#      响应缓存到 CHECK_RESP，status 不再重复请求
#   9. 增加 java/curl 命令可用性检查、mkdir -p、变量统一加引号等健壮性修复
#  10. stop 支持多 PID 逐个终止（原 kill $pid 只杀第一个）
#  11. test_server 移出守护循环主体，java 快速崩溃时不会堆积监控子进程
#  12. 超时/等待等魔法数字提取为顶部变量，便于调整
#
# 特别注意：
#   该脚本使用系统 kill 命令来强制终止指定的 java 程序进程。
#   所以在杀死进程前，可能会造成数据丢失或数据不完整。
#   如果必须要考虑到这类情况，则需要改写此脚本。
# =============================================================================

# JAVA应用程序的名称
APP_NAME="mesh_server"
SHELL_NAME=$(basename "$0")
JAVA_MAIN="cn.net.zhijian.platform.ServerMain"
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)   # 脚本位于 sbin 下，APP_HOME 为 server 根目录
# 切换到 server 根目录：保证 java 进程的 user.dir 始终指向 sbin 的上一层目录。
# ServerMain 通过 System.getProperty("user.dir") 定位 conf 目录，
# 从任意路径调用本脚本时若不切换，会导致配置/日志初始化失败。
cd "${APP_HOME}" || exit 1
PID_FILE="${APP_HOME}/.pid"                  # 存放进程PID的文件，同时表示是否继续运行
LOGS="${APP_HOME}/logs"
LIBPATH="${APP_HOME}/libs"
CHECK_URL="http://localhost:8523/backend/api/checkup"   # 健康检查接口地址
OMPWD="$2"
MAINBIOS="$3"

# 等待/超时参数（秒）
START_WAIT_SEC=30     # 等待服务启动完成的最长时间
STOP_WAIT_SEC=30      # 等待服务停止完成的最长时间
RESTART_SLEEP_SEC=3   # 重启前等待时间

# ---- classpath 构建 ----
# 使用 glob + 存在性判断，避免裸 ls 在无 jar 时输出错误；
# 显式以 .:${APP_HOME}/bin/main 开头（主类目录），不再依赖 shell 隐式空路径元素
CP=".:${APP_HOME}/bin/main"
for dir in "${LIBPATH}" "${APP_HOME}/sbin/dependency"; do
    for jar in "${dir}"/*.jar; do
        if [ -f "${jar}" ]; then
            CP="${CP}:${jar}"
        fi
    done
done

# ---- java 虚拟机启动参数（数组，避免分词问题）----
JAVA_OPTS=(-Xms512m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=utf-8)
JAVA_OPTS+=(-XX:MaxMetaspaceSize=1024m -XX:ParallelGCThreads=1)
JAVA_OPTS+=(-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOGS}/HeapDump.hprof)

# netty 需要的配置，java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent
JAVA_OPTS+=(--add-opens java.base/jdk.internal.misc=ALL-UNNAMED)
# netty 提示 Reflective setAccessible(true) disabled
JAVA_OPTS+=(-Dio.netty.tryReflectionSetAccessible=true)
# netty 提示 java.nio.DirectByteBuffer.<init>(long, {int,long}): unavailable
JAVA_OPTS+=(--add-opens java.base/java.nio=ALL-UNNAMED)
# 解决日志配置文件加载问题
JAVA_OPTS+=(-Dlogs.root=${LOGS} -Dlogback.configurationFile=${APP_HOME}/conf/logback.xml)

# 检查必需命令是否存在
for cmd in java curl; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: required command '${cmd}' not found in PATH" >&2
        exit 1
    fi
done

# ---- 工具函数 ----

# 查询服务进程 PID 列表（等价 ps -ef | grep -Dname=mesh_server | grep -v grep）
get_pids() {
    local match="-Dname=${APP_NAME}"
    # shellcheck disable=SC2009
    ps -ef | awk -v m="${match}" '/java/ && index($0, m) > 0 { print $2 }'
}

# 检查程序是否处于运行状态
# 返回值：0=进程存在，1=进程不存在
is_exist() {
    local pids
    pids=$(get_pids)
    [ -n "${pids}" ]
}

# 健康检查的 JSON 响应（is_started 内部填充，供调用方直接使用，避免重复请求）
CHECK_RESP=""

# 只有返回 0 时才是成功，1 为未启动，2 为吊死
is_started() {
    if ! is_exist; then
        CHECK_RESP=""
        return 1
    fi

    # 正常的情况下，返回一个包含 "code":0 的 json 字符串
    CHECK_RESP=$(curl -s --max-time 3 "${CHECK_URL}")
    # 解析 JSON 中的 code 字段（无外部依赖，仅用 grep/sed）
    local code
    code=$(printf '%s' "${CHECK_RESP}" \
        | grep -o '"code"[[:space:]]*:[[:space:]]*[-0-9]*' \
        | head -n1 \
        | grep -o '[-0-9]*$')
    if [ "${code}" = "0" ]; then
        return 0
    fi
    return 2
}

# 查询进程 CPU/内存占用（ps 的 %cpu 为进程生命周期内的平均占用，近似 top 的 %CPU）
cpu_mem_usage() {
    local pid="$1"
    ps -p "${pid}" -o %cpu=,%mem= --no-headers 2>/dev/null \
        | awk '{printf "CPU: %s%% MEM: %s%%", $1, $2}'
}

# ---- 核心功能函数 ----

# 服务启动方法
start() {
    is_started
    local st=$?
    if [ "${st}" -eq 0 ]; then
        local pids
        pids=$(get_pids | tr '\n' ',')
        echo "${APP_NAME} is already running, pid is ${pids%,}"
    elif [ "${st}" -eq 1 ]; then
        start_loop
    else
        # 进程吊死：先停止再启动
        stop
        start_loop
    fi
}

# 循环启动脚本（后台守护）：java 异常退出后自动重启，直到 .pid 文件被删除
start_loop() {
    mkdir -p "${LOGS}"
    echo "==============================================="

    trap "" HUP # 忽略HUP信号，允许终端关闭后服务程序继续运行
    : > "${PID_FILE}"  # 创建/清空 .pid 文件，守护循环以此判断是否继续

    (
        while true; do
            # 组装 java 参数：-classpath 单独传参，避免路径含空格时被分词；
            # om_pwd / main_bios 未提供时不传空参数
            local args=("${JAVA_OPTS[@]}" -classpath "${CP}" "${JAVA_MAIN}")
            if [ -n "${OMPWD}" ]; then
                args+=("${OMPWD}")
            fi
            if [ -n "${MAINBIOS}" ]; then
                args+=("${MAINBIOS}")
            fi
            # 在此堵塞，直到 java 进程退出
            java "${args[@]}" &> /dev/null
            if [ ! -f "${PID_FILE}" ]; then # .pid 文件不存在，则终止
                break
            fi
        done
    ) &

    # 后台执行一次启动检测（与守护循环分离，java 快速崩溃时也不会堆积监控进程）
    test_server &
}

# 启动检测：等待服务启动成功并输出结果
test_server() {
    echo "Starting"
    local count=0
    local success=0
    while true; do
        is_started
        if [ $? -eq 0 ]; then
            success=1
            break
        fi
        if [ "${count}" -gt "${START_WAIT_SEC}" ]; then
            break
        fi
        if [ ! -f "${PID_FILE}" ]; then
            break
        fi
        sleep 1s
        echo -n "."
        ((count++))
    done
    echo "" # 输出换行

    if [ "${success}" -eq 1 ]; then
        sleep 1s
        local pid
        pid=$(get_pids | head -n1)
        echo "${pid}" > "${PID_FILE}"
        echo "start ${APP_NAME} successed, pid is ${pid}"
        echo "${CHECK_RESP}"
        echo ""
    else
        echo "start ${APP_NAME} failed"
    fi
}

# 服务停止方法
stop() {
    if is_exist; then
        rm -rf "${PID_FILE}" # 守护循环判断 .pid 不存在则 break
        local pids
        pids=$(get_pids)
        echo -n "Stopping server ${pids//$'\n'/,}"
        local pid
        for pid in ${pids}; do
            kill "${pid}"
        done

        # 循环判断服务进程是否存在
        local count=0
        local stopped=0
        while true; do
            if ! is_exist; then
                stopped=1
                break
            fi
            if [ "${count}" -gt "${STOP_WAIT_SEC}" ]; then
                break
            fi
            sleep 1s
            echo -n "."
            ((count++))
        done
        echo "" # 输出换行

        if [ "${stopped}" -eq 0 ]; then # 没有正确停止，则强制停止
            for pid in ${pids}; do
                echo "kill -SIGINT ${pid}"
                kill -SIGINT "${pid}"
            done
        fi
    else
        rm -rf "${PID_FILE}" # 守护循环判断 .pid 不存在则 break
    fi
    echo "${APP_NAME} process stopped!"
}

# 服务运行状态查看方法
status() {
    is_started
    local st=$?
    if [ "${st}" -eq 0 ]; then
        local pid
        pid=$(get_pids | head -n1)
        echo "${APP_NAME} is running,pid is ${pid}"
        echo "${CHECK_RESP}"
        cpu_mem_usage "${pid}"
    elif [ "${st}" -eq 1 ]; then
        echo "${APP_NAME} is not running!"
    else
        echo "${APP_NAME} is hung up!"
    fi
}

# 重启服务方法
restart() {
    # 调用服务停止命令
    stop
    echo "Wait a moment"
    sleep "${RESTART_SLEEP_SEC}s"
    # 调用服务启动命令
    start
}

# 帮助说明，用于提示输入参数信息
usage() {
    echo "Usage: sh ${SHELL_NAME} start|stop|restart|status [om_pwd [main_bios]]"
    exit 1
}

CMD="$1"

case ${CMD} in
    'start')
        start
        ;;
    'stop')
        stop
        ;;
    'restart')
        restart
        ;;
    'status')
        status
        ;;
    *)
        usage
        ;;
esac
exit 0
