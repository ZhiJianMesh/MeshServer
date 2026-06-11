package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.DateUtil;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 版本源文件备份
 */
public class Source extends AbsCommand implements IConst {
    private static final int BUFFER_SIZE = 4 * 1024 * 1024;
    
    public Source(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        if(args.length < 1) {
            printHelp(help());
            return false;
        }
        String cmd = args[0];
        List<Project> prjs = parseCfg(StringUtil.removeEle(args, 0));
        if(cmd.equals("backup")) {
            return srcBackup(prjs);
        }
        return srcStat(prjs);
    }
    
    private boolean srcBackup(List<Project> prjs) throws Exception {
        for(Project p : prjs) {
            if(p.backup) {
                compressSource(p);
            }
        }
        return true;
    }
    /**
     * 压缩服务端安装包，包括UI与接口定义
     * @param prj release配置信息
     * @return 压缩文件数量
     */
    private void compressSource(Project prj) {
        File outputDir = new File(prj.outputPath);
        if(!outputDir.exists()) {
            outputDir.mkdirs();
            System.out.println("`" + prj.outputPath + "` not exists");
        }
        FileUtil.remove(prj.zip);
        System.out.println("Compress package '" + prj.name + "':" + prj.outputPath);

        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(prj.zip))) {
            int n = 0;
            for(String p : prj.paths) {
                n += zipOneDir(prj, new File(p), p, zos);
            }
            System.out.println("Completed '" + prj.name + "':" + prj.zip + "," + n + " files");
        } catch (IOException e) {
            System.out.println("Fail to Compress files under " + prj.outputPath + " to " + prj.zip);
            e.printStackTrace();
        }
    }
    
    private int zipOneDir(Project p, File zipPath, String root, ZipOutputStream zos) throws IOException {
        File[] subs = zipPath.listFiles();
        if (subs == null || subs.length == 0) {
            return 0;
        }

        int n = 0;
        String ext, entryName;
        for (File file : subs) {
            entryName = FileUtil.zipEntryName(file, root);
            if(p.excludes.contains(entryName)) {
                continue;
            }
            
            if(file.isDirectory()) {
                if(file.getName().startsWith("_")) {
                    continue;
                }
                if(!StringUtil.isEmpty(entryName)) {
                    ZipEntry zipEntry = new ZipEntry(entryName + '/'); //不管操作系统的分隔符，统一使用/
                    zos.putNextEntry(zipEntry);
                }
                n += zipOneDir(p, file, root, zos);
                continue;
            }
            
            ext = FileUtil.getFileExtension(file.getName());
            if(!p.includes.contains(ext)) {
                continue;
            }
            
            if(ext.equalsIgnoreCase("zip")) {
                if(p.isDestZip(file)) {
                    continue; //不能压缩自身
                }
            }
            FileUtil.zipOneFile(root, file, zos);
            n++;
        }
        return n;
    }
    
    private List<Project> parseCfg(String[] args) {
        File cfgFile;
        if(args.length > 0) {
            cfgFile = new File(args[0]);
            if(!cfgFile.exists()) {
                cfgFile = new File(System.getProperty("user.dir") + File.separatorChar + args[0]);
            }
        } else {
            cfgFile = new File(System.getProperty("user.dir") + File.separatorChar + "source.cfg");
        }
        
        if(!cfgFile.exists()) {
            System.out.println(cfgFile + " not exists");
            return null;
        }
        
        Map<String, Object> cfg = JsonUtil.jsonFileToMap(cfgFile, true);
        if(cfg == null || cfg.size() == 0) {
            System.out.println(cfgFile + " is invalid");
            return null;
        }
        List<Project> prjs = new ArrayList<>();
        List<String> defaultIncludes = ValParser.getAsStrList(cfg, "includes");
        List<String> defaultExcludes = ValParser.getAsStrList(cfg, "excludes");
        String defaultOutput = ValParser.getAsStr(cfg, "output");
        List<Object> list = ValParser.getAsList(cfg, "projects");

        for(Object o : list) {
            Map<String, Object> p = ValParser.parseObject(o);
            prjs.add(new Project(p, defaultOutput, defaultIncludes, defaultExcludes));
        }
        return prjs;
    }
    
    private boolean srcStat(List<Project> prjs) throws Exception {
        int lineNum;
        int allTotal = 0;
        for(Project p : prjs) {
            lineNum = statPaths(p.paths, p);
            if(p.countToTotal) {
                allTotal += lineNum;
            }
            System.out.println("== " + p.name);
            System.out.println("Result:" + p.result.toString() + ",total:" + lineNum + "\n");
        }
        System.out.println("All total:" + allTotal);
        return true;
    }

    private static int statPaths(String[] pl, Project prj) {
        int total = 0;
        int lineNum;
        if(pl.length > 1) {
            lineNum = 0;
        }
        for(String p : pl) {
            lineNum = statPath(new File(p), prj);
            total += lineNum;
        }
        return total;
    }
    
    private static int statPath(File p, Project prj) {
        File[] ff = p.listFiles();
        if(ff == null || ff.length <= 0) {
            return 0;
        }
        String name, type;
        int pos;
        int total = 0;
        byte[] buff = new byte[BUFFER_SIZE];
        FileInputStream fis = null;
        int readLen;
        int lineNum;
        
        for(File f : ff) {
            if(f.isDirectory()) {
                total += statPath(f, prj);
                continue;
            }
            name = f.getName();
            pos = name.lastIndexOf('.');
            if(pos <= 0) {
                continue;
            }
            
            type = name.substring(pos + 1);
            if(!prj.needStat(type)) {
                continue;
            }
            
            try {
                fis = new FileInputStream(f);
                do{
                    readLen = fis.read(buff);
                    lineNum = 0;
                    for(int i = 0; i < readLen; i++) {
                        if(buff[i] == '\n') {
                            lineNum++;
                            total++;
                        }
                    }
                    prj.record(type, lineNum);
                } while(readLen == BUFFER_SIZE);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if(fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return total;
    }
    
    @Override
    public String[] help() {
        return new String[]{
            "source file commands",
            name + " backup|stat",
            "1)backup [path_to_config_file]",
            "2)stat [path_to_config_file]"
        };
    }
    
    private static class Project {
        static final String Time = DateUtil.utcToLocale(System.currentTimeMillis(), "yyyy-MM-dd");
        final String[] paths;
        final String name;
        final boolean countToTotal;
        final boolean backup;
        final Map<String, Integer> result = new HashMap<>();
        final String outputPath;
        final Set<String> includes = new HashSet<>();
        final Set<String> excludes = new HashSet<>();
        final File zip;

        Project(Map<String, Object> cfg, String defaultOutput, List<String> defaultIncludes, List<String> defaultExcludes) {
            Object p = cfg.get("path");
            this.name = ValParser.getAsStr(cfg, "name");
            this.outputPath = ValParser.getAsStr(cfg, "output", defaultOutput);
            if(p instanceof List) {
                List<String> l = ValParser.parseAsStrList(p, "");
                this.paths = new String[l.size()];
                int i = 0;
                for(String s : l) {
                    this.paths[i++] = s;
                }
            } else {
                this.paths = new String[] {p.toString()};
            }
            List<String> ss = ValParser.getAsStrList(cfg, "statTypes");
            if(ss == null || ss.size() == 0) {
                result.put("java", 0);
            } else {
                for(String s : ss) {
                    result.put(s.trim(), 0);
                }
            }
            this.countToTotal = ValParser.getAsBool(cfg, "countToTotal", true);
            this.backup = ValParser.getAsBool(cfg, "backup", true);
            
            List<String> list = ValParser.getAsStrList(cfg, "includes");
            if(list != null) {
                includes.addAll(list);
            }
            includes.addAll(defaultIncludes);
            
            list = ValParser.getAsStrList(cfg, "excludes");
            if(list != null) {
                excludes.addAll(list);
            }
            excludes.addAll(defaultExcludes);
            this.zip = new File(FileUtil.addPath(outputPath, name + "@" + Time + ".zip"));
        }
        
        boolean isDestZip(File f) {
            return f.equals(zip);
        }
        
        void record(String type, int lineNum) {
            int n = result.get(type);
            result.put(type, n + lineNum);
        }
        
        boolean needStat(String type) {
            return result.containsKey(type);
        }
    }
}
