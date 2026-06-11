package cn.net.zhijian.platform.cmd;

import java.util.concurrent.TimeUnit;

import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient;
import cn.net.zhijian.util.FileUtil;

public class Download extends AbsCommand {
    public Download(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        String url = args[0];
        String saveAs;
        if(args.length > 1) {
            saveAs = args[1];
        } else {
            int pos = url.lastIndexOf('/');
            saveAs = FileUtil.addPath(System.getProperty("user.dir"), url.substring(pos + 1));
        }

        int timeout = 1000;
        if(args.length > 2) {
            timeout = Integer.parseInt(args[2]);
        }
        
        try {
            HandleResult hr = HttpClient.download(url, null, saveAs).get(timeout, TimeUnit.SECONDS);
            System.out.println("over,result:" + hr.brief());
            return true;
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String[] help() {
        return new String[]{name + " url [saveTo] [timeout]"};
    }
}
