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
APP_NAME="mesh_command"
HOME=$(cd `dirname $0`/..; pwd)
# PID 代表是PID文件
PID_FILE="${HOME}/.pid"
LOGS="${HOME}/logs"
LIBPATH="${HOME}/libs"
CP=""

#将lib目录下的所有jar加入classpath
for jar in `ls ${LIBPATH}/*.jar`; do
    CP=${CP}:$jar
done

#将dependency目录下的所有jar加入classpath
for jar in `ls ${HOME}/sbin/dependency/*.jar`; do
    CP=${CP}:$jar
done

# java虚拟机启动参数
JAVA_OPTS="-Dname=${APP_NAME} -Xms128m -Xmx128m -XX:+UseSerialGC -Dfile.encoding=utf-8"
JAVA_OPTS=${JAVA_OPTS}" -Dlogback.configurationFile=${HOME}/conf/cmdlogback.xml"
JAVA_OPTS=${JAVA_OPTS}" -Dos.name=Linux -classpath ${CP}"
# 启动脚本
java ${JAVA_OPTS} cn.net.zhijian.platform.ToolMain $1 $2 $3 $4 $5 $6 $7 $8 $9
