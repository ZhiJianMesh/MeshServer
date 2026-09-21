# 一、 Android手机安装OpenClaw

现在的Android手机8核处理器、8G内存、128G存储的配置很常见，这种配置的手机4、5年前就已经出现了，现在淘汰的应该也不少。
如果用它来养龙虾非常合适，性能完全满足，功耗很低，24小时插电，运行时无任何噪声，再配合飞书、DeepSeek大模型，用来做一些简单的数据收集分析是没问题的，做自动编码也可以。

## Termux安装

Termux是一个安卓应用，安卓内核是Linux，Termux在安卓内核的基础上加了一个适配层，提供了一个精简的Linux程序运行环境。从github[下载Termux应用](https://github.com/hanxinhao000/ZeroTermux/releases)，选择最新arm64 release版本。下载后拷贝到安卓手机中，在文件管理中找到下载的apk文件，点击就能安装。

如果无法访问github，安装[watt加速工具](https://apps.microsoft.com/detail/9mtcfhs560ng?launch=true&hl=zh-CN&gl=CN)，运行加速就可以访问了。

因为不是从厂商的应用市场下载安装的，所以安装过程会有告警，请忽略所有告警。

单纯的openclaw消耗的资源不多，一部手机跑四、五个agent没什么问题。如果要求所有信息都不出内网，就需要运行本地模型，单靠一部手机是不可能的。所以要么使用其他机器安装ollama+大模型，要么使用DeepSeek等厂商提供的API接口。我使用的是第三方厂商提供的API接口，所以本地资源消耗极少。

因为不是从手机厂商的应用市场安装，所以安装过程会有告警，请忽略所有告警。


### A）工具安装

Termux安装完成后，打开应用，显示一个命令行工具，使用pkg命令（对应于linux中的apt）安装以下工具：

|命令|用处|
|---|---|
|pkg install termux-auth -y	|提供passwd命令，设置或修改用户密码|
|passwd	|设置root用户的秘密，termux中可以直接root用户访问|
|pkg install termux-services -y	|服务管理，比如运行sv-enable sshd|
|pkg install openssh - y|sshd服务，用于远程命令行操控|
|sshd|启动sshd服务，这时可以使用Bitvise等工具远程连接|
|pkg install nodejs -y|openclaw依赖nodejs服务，可以使用后面的脚本统一安装，也可以这里手动安装，避免脚本中安装老版本，导致后面提示nodejs版本不正确|
|pkg install python -y|如果需要让龙虾做一些自动化工具，用python是比较方便的，特别是数据分析之类的|

安装了openssh服务后，可以通过Bitvise等工具在PC上登录（端口号选择8022，而不是22），在键盘上操作更加方便快捷。

每次在termux中安装新工具前，建议运行一下 pkg update && pkg upgrade 命令及时更新系统。

运行 termux-info可以查看termux、linux、android的版本信息。

### B）必要的配置

1. 用nano命令在home目录下编辑”.bashrc”文件（注意文件前面有个点），添加以下内容；
```bash
alias ll=’ls -l’
```
如果需要查看隐藏文件可以使用 alias ll='ls -lA'

2. 修改$PREFIX/etc/apt/sources.list使用清华的镜像，提升安装速度；
```bash
deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main
```
3. **termux-wake-lock**：让termux保持运行，忽略电池优化，运行后手机上会弹出窗口确认，请选择“总是允许”（每个品牌不同版本都不同）；
4. **sv-enable sshd**：使sshd服务自动启动（第一次要用sshd启动）；
5. **termux-setup-storage**：允许访问外部存储（可以不设置）。

## 安装OpenClaw

直接在termux环境安装会遇到很多不兼容问题，国内版openclaw-cn-termux解决了一些问题，但是它内嵌的openclaw版本太低0.2.0，还会遇到不兼容问题，比如原生clipboard问题、无法写/tmp问题等。

最终选择的安装工具是一个脚本，能在termux中直接安装，脚本在github中能[下载](https://github.com/hillerliao/install-openclaw-on-termux)。
脚本中解决了clipboard原生映射问题、/tmp无法写的问题。美中不足的是脚本中安装glibc-runner部分要做修改，所以，先用命令行运行以下命令：

```BASH
pkg install glibc-repo -y
pkg install glibc-runner -y
```

因为手机中不能正常访问github，所以在PC环境浏览器中直接[下载](https://raw.githubusercontent.com/hillerliao/install-openclaw-on-termux/refs/heads/main/install-openclaw-termux.sh)，将内容存到install.sh，然后ftp传到termux中。

替代脚本中的“run_cmd pacman -Sy glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf”替换为以下内容：

```BASH
run_cmd pkg install glibc-repo -y
run_cmd pkg install glibc-runner -y
```

# 二、 其他系统中安装OpenClaw
在Linux、Windows、Mac系统中安装，可以查看[OpenClaw](https://openclaws.io/zh/)首页，可以一行命令完成，所以在此不赘述。


# 三、 OpenClaw配置

在不同的操作系统中安装的openclaw，配置方法都是相同的，主要分成三块：model模型、channel通道、agent代理人/智能体。运行openclaw onboard一步步地配置。

## 1. 模型
在onboard界面中输入configure a model，会显示当前支持的模型，如果看不到，选择more，回车，就可以看到更多，选中你使用的模型就可以安装模型的插件，安装完成后输入key即可。

可以设置多个模型，每个代理人可以使用不同的模型。当前DeepSeek的模型最便宜(2026.8)，DeepSeek的key在创建时要拷贝下来，因为后面就无法拷贝了。

## 2. 飞书智能体

以飞书为例，在[飞书开放平台](https://open.feishu.cn/?lang=zh-CN)的右上角点击“开发者后台”，进入后点“立即创建”创建一个智能体，创建后可以获得App ID与App Key，这两个信息在配置通道时用到。每个飞书智能体（应用）对应一个openclaw的代理人（agent）。

设置应用名称、应用描述等，然后配置权限与事件回调。

事件回调中，首先将订阅方式配置成长连接，并添加这些事件：机器人进群、机器人被移出群、消息被reaction、消息被取消reaction、接收消息、消息撤回、消息已读。

权限设置可以批量导入以下内容：
```JSON
{
"scopes": {
    "tenant": [
      "aily:file:read",
      "aily:file:write",
      "application:application.app_message_stats.overview:readonly",
      "application:application:self_manage",
      "application:bot.menu:write",
      "cardkit:card:write",
      "contact:user.employee_id:readonly",
      "corehr:file:download",
      "docs:document.content:read",
      "event:ip_list",
      "im:chat",
      "im:chat.access_event.bot_p2p_chat:read",
      "im:chat.members:bot_access",
      "im:message",
      "im:message.group_at_msg:readonly",
      "im:message.group_msg",
      "im:message.p2p_msg:readonly",
      "im:message:readonly",
      "im:message:send_as_bot",
      "im:resource",
      "sheets:spreadsheet",
      "wiki:wiki:readonly"
    ],
    "user": [
      "aily:file:read",
      "aily:file:write",
      "im:chat.access_event.bot_p2p_chat:read",
      "im:message",
      "im:message.draft_write_as_user",
      "im:message.group_msg:get_as_user",
      "im:message.p2p_msg:get_as_user",
      "im:message.pins:read",
      "im:message.pins:write_only",
      "im:message.reactions:read",
      "im:message.reactions:write_only",
      "im:message.send_as_user",
      "im:message.urgent.status:write",
      "im:message:readonly",
      "im:message:recall",
      "im:message:update"
    ]
  }
}
```

## 3. 通道

用来给agent发送命令的通道，可以选飞书、微信、QQ等，其中飞书，可能因为后起之秀，为了竞争，对公司设置无限制，组群比较灵活。在openclaw onboard的channels中已内置可选，飞书上创建智能体应用也很简单。

如果安装微信、QQ通道，需要运行插件安装命令，比如微信插件的命令如下：
```BASH
openclaw plugins install "@tencent-weixin/openclaw-weixin"
```
不推荐微信与QQ的原因是建群需要企业帐号，而飞书中随便输入一个企业名称就可以了，企业不必非得存在。

在onboard界面输入connect feishu，输入上一节代理人中的App ID与App Key即可将第一个代理人与飞书智能体关联起来。在飞书客户端中，打开智能体，随便发一个消息，它会提示在openclaw上运行一个确认命令，命令执行完毕就完成了连接。

一旦建立了第一个代理人，其他的代理人可以让第一个代理人创建。根据需要创建多个代理人，每个代理人设置不同的角色。多角色的好处是可以各司其职，从技术看，每个角色的memory是单纯的，不必在多种角色之间切换。

## 4. 代理人灵魂

每个代理人需要具备不同的特征，这样才可以互相配合，发挥各自的优势。

代理人灵魂是记录在它对应的workspace目录的SOUL.md文件中，可以直接修改它，也可以让代理人自己修改。手动修改的情况，可以让代理人重新加载SOUL。
代理人的SOUL可以在deepseek等工具中输入你希望它具备的特质，让deepseek帮忙生成，然后拷贝到SOUL.md文件中。修改了SOUL后需要重启openclaw。

## 5. 飞书群组
在飞书客户端，点击右上角“⨁”按钮创建群组。点击群组界面右上角“…”图标进入设置界面，在群机器人中添加已经创建的智能体。后面的工作与在普通工作群中是一样的。

建议将其中一个智能体设置成领导，负责分配工作，直接向你负责，其他智能体听从领导的安排，完成后向领导汇报，然后领导再向你汇报最终进展。

# OpenClaw技能
技能是一个功能的说明书，指导openclaw怎么做。

## 安装技能
从clawhub安装：clawhub install 技能名称
从公共的技能库安装： openclaw skills install @所有者/技能名

比如：
```BASH
#安装clawhub工具
npm i -g clawhub
#安装至简网格技能
openclaw skills install @flyinmind/mesh-app-factory
```

## 创建技能

建议使用openclaw的agent实现，将SKILL.md文档及相关的例子等上传到openclaw所在机器，然后给agent发命令，让它创建技能并打包，也可以让它安装。

## 上传技能
上传clawhub，需要在clawhub注册帐号，然后让agent上传。
也可以手动上传，上传前会给出一个连接，在浏览器中打开，选择Authorize确认授权即可。

```BASH
clawhub skill publish SKILL.md所属的完整路径 --slug URL路径(可以与name相同) --name "名称"
```

slug比较费解，比如slug设为mesh-app-factory，帐号为flyinmind，则最终的访问URL为：

```URL
https://clawhub.ai/flyinmind/skills/mesh-app-factory
```
后面的修改通过以下命令进行：

```BASH
openclaw skills update <skill-name>
```

# OpenClaw插件

openclaw有个工具箱，每个插件是工具箱中的一个工具，可以是消息渠道、模型提供商、本地CLI后端、智能体工具、钩子、媒体提供商等，增强openclaw与其他系统交互的能力。飞书、微信等通道能力就是使用插件实现的。

## 安装插件
```BASH
openclaw plugins install clawhub:<package-name>
```

## 创建插件

插件本质是一个nodejs模块，请参照[官方文档](https://docs.openclaw.ai/zh-CN/plugins/building-plugins)实现。
也可以将插件功能描述清楚，给openclaw中的agent发命令，让它完成插件实现。

## 上传插件
