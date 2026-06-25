<div align="center" style="font-size:2em;font-weight:bold;">
  MeshServer安装与运行<br>
  <img src="imgs/zhijian_logo.png" width="50">
</div>

# 摘要

本文讲解MeshServer安装与运行的方法。

# 一、安装

## 运行环境安装

在Linux、Window、Termux中只需安装OpenJDK11及以上版本即可，安装方法请参照[运行环境安装](compile_run_env.md)。

## JVM环境

Linux、Termux、Windows中使用Java运行服务器。Linux、Termux中需要创建mesh用户，以mesh身份下载、安装、运行。

1. 创建server目录；
2. 从[码云](https://gitee.com/zhijian_net/MeshServer)或[GitHub](https://github.com/ZhiJianMesh/MeshServer)下载发布的版本；
3. 解压安装包到server目录，进入server目录；
4. 访问`http://www.zhijian.net.cn/`，点右上角菜单“使用指南”，选择“注册”，进入注册界面，输入注册信息获得注册命令行；
![register](imgs/install/register.png)
5. 在server目录下运行注册命令，等待注册成功；
6. 在server目录下运行启动命令，等待启动成功。


## 安装Android服务器
Android环境的服务器本质是一个驻留后台的安卓应用，与普通应用没有任何差异，所以安装运行与普通应用也没有任何差异，不需要提前安装运行环境。
1. 从[码云](https://gitee.com/zhijian_net/MeshServer)或[GitHub](https://github.com/ZhiJianMesh/MeshServer)下载发布的安装服务器版本；
2. 安装服务器应用，因为不是从厂商的应用市场下载，安装时会有告警，请忽略；
3. 第一次启动会自动弹出登录&注册界面，如果尚未注册，请先输入信息注册；
![register](imgs/install/android_register.png)
4. 注册完成后，输入公司id及密码登录；
![register](imgs/install/android_login.png)
5. 登录完成后就可以点击启动按钮启动服务器。



