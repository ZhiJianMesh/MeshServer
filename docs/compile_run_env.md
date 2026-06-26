<div align="center" style="font-size:2em;font-weight:bold;">
  Mesh编译&运行环境<br>
  <img src="imgs/zhijian_logo.png" width="50">
</div>

# 摘要

本文讲解Mesh开发、运行环境安装方法，以及所需原生库跨平台编译方法。


# 一、Java编译&运行环境

至简网格服务器运行只依赖Java运行环境，如果不涉及Java源码修改，安装JRE即可，请在网上自行搜索安装方法。以下描述的是Java开发环境安装。

## 1、Linux环境
Linux服务器提供apt包管理软件（阿里云提供dnf包管理软件，其他云服务提供商可能有不同的命令，请自行搜索）。
如果有现成的包管理软件，直接运行命令安装openjdk即可，最小版本11，比如Ubuntu中安装命令如下：
```bash
apt install -y openjdk-21
```

如果没有包管理工具，按以下步骤安装。

1. 登录root，ubuntu默认不用root，通过sudo passwd root设置root密码，然后用root登录；
2. 下载openjdk（比如`https://jdk.java.net/java-se-ri/11-MR3`），并上传到服务器的/jdk目录下；
3. 解压后，使用nano编辑/etc/profile，在末尾增加以下内容；

```bash
export JAVA_HOME=/jdk/openjdk11
export PATH=$JAVA_HOME/bin:$PATH
```

4. 运行“点”命令，使profile立刻生效；

```bash
. /etc/profile
```
以上步骤就完成了java的安装，下面是创建mesh用户，后继操作都是以mesh用户身份运行。

1. 创建mesh用户；
```bash
useradd -m -d /home/mesh -s /bin/bash mesh
```

注意指定bash，如未指定可以用chsh -s /bin/bash改正

2. 设置mesh用户密码(有密码与密码确认步骤)；
```bash
passwd mesh XXXXX
```

3. 退出root，登录mesh帐号，服务程序安装都**以mesh用户身份操作**。

## 2、Windows环境

下载openjdk21，解压后，在“我的电脑”或“此电脑”图标上点击右键菜单，选择属性->高级系统设置，在系统变量中增加JAVA\_HOME，指向解压目录；然后在PATH中增加%JAVA\_HOME%\bin，java就安装好了。

![wininstalljdk](imgs/compile/wininstalljdk.png)

## 3、Android环境

Android环境的服务器本质是一个长期驻留后台的安卓应用，安卓版本在7.0（2016.5发布）及以上版本就可以。

## 4、Termux-Linux环境

Termux是一个安卓应用，下载安装就能运行。因为安卓内核是Linux，Termux在安卓的基础上加了一个Linux适配层，实现一个精简的Linux环境。

至简网格服务器坚持极小的外部依赖，所以可以运行在这种资源极其有限的环境中，仍然可以实现多实例、多区域集群能力，即便于开发测试，也能以极小的成本运行。

从github下载Termux应用，连接为`https://github.com/termux/termux-app/releases`。选择合适的版本，下载后在安卓手机中安装，安卓版本至少为7.0。
因为不是从厂商的应用市场下载安装的，所以安装过程会有告警，请忽略所有告警。

### 工具安装

Termux安装完成后，使用pkg命令（对应于linux中的apt）安装以下工具：

| 命令 | 用处 |
| --- | --- |
| pkg install termux-auth -y | 提供passwd命令，设置或修改用户密码 |
| pkg install termux-services -y | 服务管理，比如运行sv-enable sshd |
| pkg install openssh -y | sshd服务，用于远程命令行操控 |
| pkg install -y openjdk-21 | Java运行环境安装 |

### 必要的配置

1. 用nano命令在home目录下编辑”.bashrc”文件（注意文件前面有个点），添加以下内容；
```bash
alias ll=’ls -l’
export LD_LIBRARY_PATH=$PREFIX/lib:/system/lib64:/system/lib
```

1. 修改$PREFIX/etc/apt/sources.list使用清华的镜像，提升安装速度；
```bash
deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main
```
2. termux-wake-lock：让termux保持运行，忽略电池优化，运行后手机上会弹出窗口确认，请选择“总是允许”（或其他类似选项，每个品牌不同版本都不同）；
3. sv-enable sshd：使sshd服务自动启动（第一次要用sshd启动）；
4. termux-setup-storage：允许访问外部存储（可以不设置）。

### ssh客户端连接

在手机中输入命令行很麻烦，在PC中使用ssh客户端连接，用键盘输入会很便捷。比如在Windows中使用Bitvise SSH客户端，注意连接的端口是8022，而不是默认的22。Linux环境也可以使用这个工具远程连接操作，如果是云服务器，需要在云上设置开放22号端口。

![sshclient](imgs/compile/sshclient.png)


# 二、原生库编译

至简网格服务器用到QuickJs与Sqlite的原生库，在Windows、Linux、Termux、Android中原生库都已提供，通常不需要C/C++开发，当然无需重新编译。
如果你有更高的要求，需要修改原生库，请按照以下建议进行。

## 1、环境准备
### Termux环境
| 命令 | 用处 |
| --- | --- |
| pkg install -y wget zip git | wget git用于下载sqlite、quickjs的源码 |
| pkg install -y clang make perl getconf cmake gradle | clang、make用于编译c源码，zip、perl是编译sqlite过程中用到的工具，getconf、cmake用于编译quickjs，gradle用于生成jar、执行单元测试。


## wsl-Ubuntu环境

之所以选择wsl，是因为在wsl与宿主Windows系统非常容易互通，且Linux中做交叉编译相对简单一些。安装方法在网上非常容易搜索到，在此不赘述。

1. 在Windows中安装wsl（Windows Subsystem for Linux）；
2. 安装完wsl后，在命令行运行wsl install安装默认的ubuntu环境；
3. Ubuntu安装后，在Windows命令行运行wsl即进入Ubuntu系统；
4. 最后安装交叉编译环境，很容易支持Linux、Windows、Android(ndk)系统的交叉编译。

### 安装Linux、Windows交叉编译工具

```bash
sudo apt update && sudo apt upgrade
sudo apt install -y make gcc g++ openjdk-21-jdk gradle
# Windows 交叉编译工具链 (MinGW)
sudo apt install -y gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64
# ARM 交叉编译工具链 (以 aarch64 为例)
sudo apt install -y gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
# Android 交叉编译工具
sudo apt install -y git make cmake
```

### 安装Android NDK

以r26c为例，其他版本的命令做适当修改即可。

```bash
cd /opt
sudo wget https://dl.google.com/android/repository/android-ndk-r26c-linux.zip
sudo unzip android-ndk-r26c-linux.zip
sudo mv android-ndk-r26c android-ndk
# 设置环境变量 (添加到 ~/.bashrc)
export ANDROID\_NDK_HOME=/opt/android-ndk
export PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
```

运行命令aarch64-linux-android21-clang --version，如果能够正确返回，则说明安装成功。
>注意：不能将NDK存在windows系统中，然后用/mnt/映射目录。

## 2、交叉编译与打包

### Sqlite-JDBC驱动原生库

Mesh没有修改Sqlitejdbc驱动，发布的版本中已提供windows、linux、mac等操作系统的原生库，所以无需为这些系统编译生成so。但是没有提供Termux中的原生库，所以需要下载源码，在termux中编译生成。

#### Termux
1. 下载源码：wget `https://github.com/xerial/sqlite-jdbc/archive/refs/tags/3.53.1.0.zip`
2. 解压源码：unzip 3.53.1.0.zip
3. 切换目录：cd sqlite-jdbc-3.53.1.0
4. 调整配置：修改Makefile.common，添加以下内容。

```Makefile
# Termux运行的设备都是aarch64位芯片，所以无需考虑x86、x86-64、arm32架构
Linux-Android-aarch64_CC := $(CROSS_PREFIX)clang
Linux-Android-aarch64_STRIP := $(CROSS_ROOT)/bin/llvm-strip
Linux-Android-aarch64_CCFLAGS := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux -I$(PREFIX)/include -Os -fPIC -fvisibility=hidden -Wno-implicit-function-declaration
Linux-Android-aarch64_LINKFLAGS := $(Default_LINKFLAGS) -shared -pthread -lm -ldl
Linux-Android-aarch64_LIBNAME := libsqlitejdbc.so
Linux-Android-aarch64_SQLITE_FLAGS :=
```

5. 编译连接：make native

    编译完成后，在target/sqlite-3.53.1-Linux-Android-aarch64目录下就有了原生库libsqlitejdbc.so

6. 重新打包

    解压对应版本的jar，解压后的目录中有META-INF、org两个子目录。将libsqlitejdbc.so拷贝到org\sqlite\native\Linux-Android\aarch64目录下，然后再将这个目录压缩成zip，并改扩展名zip为jar。

### QuickJS原生库

#### Linux/Windows/Android

进入wsl环境，在QuickJs工程目录下运行make生成windows、linux、android的原生库。
```bash
make all
```
windows、linux的jar使用gradle jar命令生成。

Android需要的是aar文件，所以先将so文件放在Android工程src/main/jniLibs相应芯片架构的目录下，然后点击右边栏gradle图标，选择项目的tasks\build\assemble生成aar文件。

#### Termux

Termux只生成Linux-Android aarch64的原生库。

```bash
make termux-aarch64
```
将生成的so复制到QuickJS工程的src/main/resources/native/Linux-Android下对应芯片架构的目录中，运行gradle jar命令重新生成jar。
