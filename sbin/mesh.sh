#!/bin/bash

# author: liguoyong
# date: 2023/3/31
#
# 特别注意：
# 该脚本使用系统kill命令来强制终止指定的java程序进程。
# 所以在杀死进程前，可能会造成数据丢失或数据不完整。
# 如果必须要考虑到这类情况，则需要改写此脚本，
#

# JAVA应用程序的名称
APP_NAME="mesh_server"
SHELL_NAME=$(basename "$0")
JAVA_MAIN="cn.net.zhijian.platform.ServerMain"
HOME=$(cd `dirname $0`/..; pwd)
PID_FILE="${HOME}/.pid" # 存放进程PID的文件，同时表示是否继续运行
LOGS="${HOME}/logs"
LIBPATH="${HOME}/libs"
CP=""
CMD="$1"
OMPWD="$2"
MAINBIOS="$3"

#将lib目录下的所有jar加入classpath
for jar in `ls ${LIBPATH}/*.jar`; do
    CP=${CP}:$jar
done

#将dependency目录下的所有jar加入classpath
for jar in `ls ${HOME}/sbin/dependency/*.jar`; do
    CP=${CP}:$jar
done

# java虚拟机启动参数
JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=utf-8"
JAVA_OPTS=${JAVA_OPTS}" -XX:MaxMetaspaceSize=1024m -XX:ParallelGCThreads=1"
JAVA_OPTS=${JAVA_OPTS}" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOGS}/HeapDump.hprof"

# netty需要的配置，java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent
JAVA_OPTS=${JAVA_OPTS}" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
# nett提示Reflective setAccessible(true) disabled
JAVA_OPTS=${JAVA_OPTS}" -Dio.netty.tryReflectionSetAccessible=true"
# netty提示java.nio.DirectByteBuffer.<init>(long, {int,long}): unavailable
JAVA_OPTS=${JAVA_OPTS}" --add-opens java.base/java.nio=ALL-UNNAMED"
# 解决日志配置文件加载问题
JAVA_OPTS=${JAVA_OPTS}" -Dlogs.root=${LOGS} -Dlogback.configurationFile=${HOME}/conf/logback.xml"

JAVA_OPTS=${JAVA_OPTS}" -Dname=${APP_NAME} -classpath $CP"

# 检查程序是否处于运行状态
is_exist() {
    # 查询出应用服务的进程id，grep -v 是反向查询的意思，查找除了grep操作的run.jar的进程之外的所有进程
    local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
    # [ ]表示条件测试。注意这里的空格很重要。要注意在'['后面和']'前面都必须要有空格
    # [ -z STRING ] 如果STRING的长度为零则返回为真，即空是真
    # 如果不存在返回0，存在返回1
    if [ -z "${pid}" ]; then
        return 0
    fi
    return 1
}

# 只有返回0时才是成功，1为未启动，2为吊死
is_started() {
    local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
    if [ -z "${pid}" ]; then
        return 1
    fi

    # 正常的情况下，返回一个json字符串
    local resp=$(curl -s "http://localhost:8523/backend/api/checkup")
    if [[ $resp == "{"* && $resp == *"}" ]]; then
        return 0
    fi
    return 2
}

# $$ Shell本身的PID（ProcessID，即脚本运行的当前 进程ID号）
# $! Shell最后运行的后台Process的PID(后台运行的最后一个进程的 进程ID号)
# $? 最后运行的命令的结束代码（返回值）即执行上一个指令的返回值 (显示最后命令的退出状态。0表示没有错误，其他任何值表明有错误)
# $- 显示shell使用的当前选项，与set命令功能相同
# $* 所有参数列表。如"$*"用「"」括起来的情况、以"$1 $2 … $n"的形式输出所有参数，此选项参数可超过9个。
# $@ 所有参数列表。如"$@"用「"」括起来的情况、以"$1" "$2" … "$n" 的形式输出所有参数。
# $# 添加到Shell的参数个数
# $0 Shell本身的文件名
# $1～$n 添加到Shell的各参数值。$1是第1参数、$2是第2参数…。
# 服务启动方法
start() {
    is_started
    if [ $? -eq "0" ]; then
        local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
        echo "$APP_NAME is already running, pid is ${pid}"
    elif [ $? -eq "1" ]; then
        start_loop
    else
        stop
        start_loop
    fi
}

start_loop() {
    # 循环启动脚本
    mkdir ${LOGS}
    echo "==============================================="

    trap "" HUP #忽略HUP信号，允许终端关闭后服务程序继续运行
    echo "" > ${PID_FILE}
    while true; do
        test_server &
        java $JAVA_OPTS $JAVA_MAIN $OMPWD $MAINBIOS &> /dev/null # 在此堵塞，直到java进程退出
        if [ ! -f $PID_FILE ]; then # running文件不存在，则终止
            break
        fi
    done &
}

test_server() {
    echo "Starting"
    local count=0;
    local success=0
    while true; do
        is_started
        if [ $? -eq "0" ]; then
            success=1
            break
        fi
        if [ $count -gt 30 ]; then
            break
        fi
        if [ ! -f $PID_FILE ]; then
            break
        fi
        sleep 1s
        echo -n "."
        ((count++))
    done
    echo "" # 输出换行

    if [ $success -eq 1 ]; then
        sleep 1s
        local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
        echo $pid > $PID_FILE
        echo "start $APP_NAME successed, pid is $pid"
        local resp=$(curl -s "http://localhost:8523/backend/api/checkup")
        echo $resp
	echo ""
    else
        echo "start $APP_NAME failed"
    fi
}

# 服务停止方法
stop() {
    is_exist
    if [ $? -eq "1" ]; then
        rm -rf $PID_FILE # 循环启动脚本中判断pid不存在则break掉
        local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
        echo -n "Stopping server ${pid}"
        kill $pid

        # 循环判断服务进程是否存在
        local count=0
        local stopped=0
        while true; do
            is_exist
            if [ $? -eq "0" ]; then
                stopped=1
                break
            fi
            if [ $count -gt 30 ]; then
                break
            fi
            sleep 1s
            echo -n "."
            ((count++))
        done
        echo "" #输出换行
        
        if [ $stopped -eq 0 ]; then # 没有正确停止，则强制停止
            echo "kill -SIGINT $pid"
            kill -SIGINT  $pid
        fi
    else
        rm -rf $PID_FILE # 循环启动脚本中判断pid不存在则break掉
    fi
    echo "$APP_NAME process stopped!"
}

# 服务运行状态查看方法
status() {
    is_started
    if [ $? -eq "0" ]; then
        local pid=`ps -ef|grep ${APP_NAME}|grep -v grep|awk '{print $2}'`
        echo "$APP_NAME is running,pid is ${pid}"
        local resp=$(curl -s "http://localhost:8523/backend/api/checkup")
        echo $resp
        top -b -n 1 -p $pid | tail -1 | awk '{print "CPU: "$9"% MEM: "$10"%"}'
    elif [ $? -eq "1" ]; then
        echo "$APP_NAME is not running!"
    else
        echo "$APP_NAME is hung up!"
    fi
}

# 重启服务方法
restart() {
    # 调用服务停止命令
    stop
    echo "Wait a moment"
    sleep 3s
    # 调用服务启动命令
    start
}

# 帮助说明，用于提示输入参数信息
usage() {
    echo "Usage: sh mesh.sh start|stop|restart|status[ om_pwd[ main_bios]]"
    exit 1
}

case $CMD in
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
