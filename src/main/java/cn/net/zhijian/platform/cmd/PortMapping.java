package cn.net.zhijian.platform.cmd;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.frm.config.ChannelConfig;
import cn.net.zhijian.util.upnp.UPnP;

public class PortMapping extends AbsCommand {
    public PortMapping(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 2) {
            printHelp(help());
            return false;
        }
        String act = args[0];
        String type = args[1];

        int port = args.length > 2 ? Integer.parseInt(args[2]) : ChannelConfig.instance().httpPort;
        CompletableFuture<Boolean> cf;
        
        if(type.equalsIgnoreCase("tcp")) {
            if("OPEN".equalsIgnoreCase(act)) {
                cf = UPnP.openTCPPort(port);
            } else if("CHECK".equalsIgnoreCase(act)) {
                cf = UPnP.isMappedTCP(port);
            } else {
                cf = UPnP.closeTCPPort(port);
            }
        } else {
            if("OPEN".equalsIgnoreCase(act)) {
                cf = UPnP.openUDPPort(port);
            } else if("CHECK".equalsIgnoreCase(act)) {
                cf = UPnP.isMappedUDP(port);
            } else {
                cf = UPnP.closeUDPPort(port);
            }
        }
        
        boolean r = cf.get(10, TimeUnit.SECONDS);
        System.out.println(act + " " + type + "  port[" + port + "],result:"
                + (r ? "succeeded" : "failed"));
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{"open a port on gateway by UPnP",
            name + " open|close|check tcp|udp [port|default_" + ChannelConfig.instance().httpPort + "]"
        };
    }
}
