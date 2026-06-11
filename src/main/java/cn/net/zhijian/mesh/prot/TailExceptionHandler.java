package cn.net.zhijian.mesh.prot;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import cn.net.zhijian.util.LogUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler.Sharable;

@Sharable
public class TailExceptionHandler extends ChannelDuplexHandler { //最终的异常处理类
    private static final Logger LOG = LogUtil.getInstance();
    private final AtomicReference<String> oldMsg = new AtomicReference<>();
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        SocketAddress remoteAddr = ctx.channel().remoteAddress();
        String msg = e.getMessage();
        //连续相同的不重复打印，防止日志攻击，只比较msg，没比较堆栈
        //可以减少部分输出，不能完全杜绝，因为并发时同时取oldMsg，都是空
        if(!msg.equals(oldMsg.get())) {
            oldMsg.set(msg);
            LOG.error("Uncaught exception on remote={}", remoteAddr, e);
        } else {
            LOG.error("Uncaught exception on remote={},msg:{}", remoteAddr, msg);
        }

        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }
}
