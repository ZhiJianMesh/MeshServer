package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 版本源文件备份
 */
public class IdleTags extends AbsCommand implements IConst {
    private static final String LANG_FILE = "language.js";
    
    public IdleTags(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        String path = "./services";
        int maxLevel = 0;
        if(args.length > 0) {
            path = args[0];
            if(args.length > 1) {
                maxLevel = Integer.parseInt(args[1]);
            }
        }
        System.out.println("path:" + path + ",maxLevel:" + maxLevel);
        return checkTags(new File(path), maxLevel);
    }
    
    private boolean checkTags(File servicesPath, int maxLevel) {
        File[] files = servicesPath.listFiles();
        if(files == null || files.length == 0) {
            return true;
        }
        
        //直接指定的就是服务目录，而不是services目录
        File serviceFilePath = new File(FileUtil.addPath(servicesPath, "file"));
        if(serviceFilePath.exists()) {
            checkOnePath(serviceFilePath, maxLevel);
            return true;
        }
        
        for(File f : files) {
            if(!f.isDirectory()) {
                continue;
            }
            serviceFilePath = new File(FileUtil.addPath(f, "file"));
            if(serviceFilePath.exists()) {
                checkOnePath(serviceFilePath, maxLevel);
            }
        }
        return true;
    }

    private void checkOnePath(File serviceFileRoot, int maxLevel) {
        File langFile = new File(FileUtil.addPath(serviceFileRoot, LANG_FILE));
        if(langFile.exists()) {
            checkOnePath(serviceFileRoot, langFile, maxLevel);
        }
    }
 
    private void initTags(String parent, int level, Map<String, Object> lang, Map<String, TagRecord> tagRecs,  int maxLevel) {
        Object o;
        Map<String, Object> sub;
        String k, k1;
        
        for(Map.Entry<String, Object> t : lang.entrySet()) {
            k = t.getKey();
            k1 = parent + k;
            if(tagRecs.containsKey(k1)) {
                continue;
            }
            tagRecs.put(k1, new TagRecord(level, k1));
            o = t.getValue();
            if(!(o instanceof Map<?,?>) || (level >= maxLevel)) {
                continue;
            }
            sub = ValParser.parseObject(o);
            if(sub != null) {
                initTags(k1 + '.', level + 1, sub, tagRecs, maxLevel);
            }
        }
    }

    private void checkOnePath(File serviceFileRoot, File langFile, int maxLevel) {
        String content = FileUtil.readFile(langFile, DEFAULT_CHARSET);
        int pos = content.indexOf('{');
        if(pos < 0) {
            System.out.println("Invalid language js file,no bracket start");
            return;
        }
        //去除首尾的非json内存
        //export default {en:{...},zh:{...}};
        content = content.substring(pos);
        pos = content.lastIndexOf('}');
        if(pos < 0) {
            System.out.println("Invalid language js file, no bracket end");
            return;
        }
        content = content.substring(0, pos + 1);
        
        Map<String, Object> langs;
        try {
            langs = JsonUtil.jsonToMapWithLF(content);
        } catch(Exception e) {
            System.out.println("Invalid language js file " + langFile);
            e.printStackTrace();
            return;
        }
        if(langs == null) {
            System.out.println("Invalid language js file " + langFile);
            return;
        }
        Map<String, TagRecord> tagRecs = new HashMap<>();
        for(Map.Entry<String, Object> e : langs.entrySet()) { //多种语言
            Map<String, Object> lang = ValParser.parseObject(e.getValue());
            if(lang == null || lang.size() == 0) {
                System.out.println("Invalid language " + e.getKey() + " in file " + langFile);
                continue;
            }
            initTags("tags.", 0, lang, tagRecs, maxLevel);
        }
        
        TagRecord[] tags = tagRecs.values().toArray(new TagRecord[] {});
        checkOnePath(serviceFileRoot, tags);
        
        System.out.println("\nIdle tags in " + langFile);
        boolean subFound;
        
        for(TagRecord c : tags) {
            if(c.count > 0) {
                continue;
            }
            
            subFound = false;
            for(Counter s : c.subs) {
                if(s.count > 0) {
                    System.out.println(c.key + "," + c.level + " ?");
                    subFound = true;
                    break;
                }
            }
            
            if(!subFound) {
                System.out.println(c.key + "," + c.level);
            }
        }
    }
    
    private void checkOnePath(File serviceFileRoot, TagRecord[] tags) {
        File[] files = serviceFileRoot.listFiles();
        if(files == null || files.length == 0) {
            return;
        }
        for(File f : files) {
            if(f.isDirectory()) {
                checkOnePath(f, tags);
                continue;
            }
            String name = f.getName();
            if(name.equals(LANG_FILE)) {
                continue;
            }
            
            String ext = FileUtil.getFileExtension(name);
            if(ext.equals("js") || ext.startsWith("htm")) { //js,htm,html
                String content = FileUtil.readFile(f, DEFAULT_CHARSET);
                for(TagRecord tr : tags) {
                    if(tr.key.equals("tags.report.vBrokerage") && name.equals("reports.js")) {
                        ext = "aaa";
                    }
                    tr.search(content);
                }
            }
        }
    }
    
    @Override
    public String[] help() {
        return new String[]{
            name + " [path_to_services][,maxLevel]"
        };
    }
    
    private static class Counter {
        final String key;
        final int keyLen;
        int count = 0;
        
        Counter(String key) {
            this.key = key;
            this.keyLen = key.length();
        }
        
        public boolean search(String content, boolean isSub) {
            if(this.count > 0) {
                return true; //已经出现过了，不必搜索
            }
            int pos = 0;
            char ch;
            
            while((pos = content.indexOf(key, pos)) > 0) {
                if(!isSub) {//子搜索前面是'.'，无需判断，否则前字符不能是字母、数字、下划线
                    ch = content.charAt(pos -  1);
                    if(Character.isLetterOrDigit(ch) || ch == '_') {
                        pos += keyLen;
                        continue;
                    }
                }
                ch = content.charAt(pos + keyLen);
                if(!Character.isLetterOrDigit(ch) && ch != '_') {
                    this.count++;
                    return true;
                }
                pos += keyLen;
            }
            
            return false;
        }
    }
    
    private static class TagRecord extends Counter {
        final int level;
        final Counter[] subs;
        
        TagRecord(int level, String key) {
            super(key);
            this.level = level;
            
            //比如tags.a.b，则需要查询'.a.b'与'.b'，确认是否存在
            List<Counter> subs = new ArrayList<>();
            int idx = 0;
            for(idx = key.indexOf('.'); idx > 0; idx = key.indexOf('.', idx + 1)) {
                subs.add(new Counter(key.substring(idx)));
            }
            if(subs.size() > 1) { //tags.xxx 没必要记录子查询
                this.subs = subs.toArray(new Counter[] {});
            } else {
                this.subs = new Counter[] {};
            }
        }
        
        public boolean search(String content) {
            if(super.search(content, false)) {
                return true;
            }
            
            for(Counter s : this.subs) {
                if(s.search(content, true)) {
                    return true;
                }
            }
            return false;
        }
    }
}
