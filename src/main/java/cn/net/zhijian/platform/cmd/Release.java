package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

//import cn.net.zhijian.mesh.frm.abs.AbsPlatform;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ClientType;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.Dependency;
import cn.net.zhijian.mesh.frm.config.ServiceInfo.ServiceType;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.JsonUtil;
import cn.net.zhijian.util.StringSpliter;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.ValParser;

/**
 * 发布服务版本，将服务打包并生成签名，同时生成appstore中数据库的初始化脚本
 */
public class Release extends AbsCommand implements IConst {
    private static final String WORKDIR = System.getProperty("user.dir");

    private static final Set<String> ServerExcludes = new HashSet<>();
    
    private static final String INSERT_SERVICE_VALUES = "('%s','%s','%s',%d,%d,%d,'%s','%s','%s',%d,%d,%d)\n";
    private static final String INSERT_SEARCH_VALUES = "('%s',%d,'%s','%s','%s',%d)\n";
    
    private static final String UPDATE_SERVICE_SQL =
            "update services set service='%s',ui='%s',type='%s',level=%d,weight=%d,ver=%d,"
            + "author='%s',displayName='%s',cmt='%s',recentUpd=%d,"
            + "update_time=%d where id=%d;\n";

    private static final String UPDATE_VER_SQL = "update vers set minCVer=%d,size=%d,"
            + "digest='%s',cmt='%s',update_time=%d where service=%d and ver=%d;\n";
    
    private static final String INSERT_RULE_SQL = "insert or ignore into rules(dstVer,update_time,service,ver,risk) values\n";
    
    private static final String INSERT_RULE_VALUES = "(%d,%d,%d,%s,1)\n";
    
    private static final String UPDATE_RULE_SQL = "update rules set dstVer=%d,update_time=%d"
            + " where service=%d and area=0 and cidStart=0 and ver=%s;\n"; //只能更新最宽的限制规则
    
    private static final String INSERT_DEPENDENCY_SEGS = "(%d,%d,%d,%d,'%s',%d),\n";
    
    private final StringBuilder insertDependencySqls = new StringBuilder(
            "insert or ignore into dependencies\n"
            + "(service,ver,dependency,minVer,features,update_time) values\n");
    private final StringBuilder insertSearchSqls = new StringBuilder(
            "delete from __docs__;\n"
            +"insert into __docs__(cls,did,title,summary,content,update_time) values\n");
    
    private final StringBuilder insertServiceSqls = new StringBuilder( "insert or ignore into services\n"
            + "(service,ui,type,level,weight,ver,author,displayName,cmt,recentUpd,update_time,id) values\n");
    private final StringBuilder updateServiceSqls = new StringBuilder();
    
    private final StringBuilder insertRuleSqls = new StringBuilder(INSERT_RULE_SQL);
    private final StringBuilder updateRuleSqls = new StringBuilder();

    private final StringBuilder insertVerSqls = new StringBuilder("insert or ignore into vers\n"
            + "(service,ver,minCVer,size,digest,cmt,update_time) values\n");
    private final StringBuilder updateVerSqls = new StringBuilder();
    
    private final Map<String, ServiceCfg> services = new LinkedHashMap<>();
    private String srcRoot, dstRoot;
    private ServiceCfg BIOS, OAUTH, WEBDB;

    public Release(String name) {
        super(name);
        ServerExcludes.add("file/_imgs");
        ServerExcludes.add("api/introduction.json");
    }

    @Override
    public boolean run(String[] paras) throws Exception {
        String[] allowedServices = null;
        String[] args = paras;
        if(args.length > 0) { //指定了服务列表，则只更新列表中的服务
            if(!args[0].endsWith(".cfg")) {
                String[] ss = args[0].split(",");
                allowedServices = new String[ss.length];
                int i = 0;
                for(String s : ss) {
                    allowedServices[i++] = s.trim();
                }
                args = StringUtil.removeEle(args, 0);
            }
        }

        String cfgFile = args.length > 0 ? args[0] : FileUtil.addPath(WORKDIR, "release.cfg");
        Map<String, Object> cfg = JsonUtil.jsonFileToMap(new File(cfgFile), true);
        srcRoot = ValParser.getAsStr(cfg, "src");
        if(srcRoot.endsWith("/") || srcRoot.endsWith("\\")) {
            srcRoot = srcRoot.substring(0, srcRoot.length() - 1);
        }
        ServiceInfo.setWorkDir(srcRoot);
        dstRoot = ValParser.getAsStr(cfg, "dst");
        if(!dstRoot.endsWith("/") && !dstRoot.endsWith("\\")) {
            dstRoot = dstRoot + File.separatorChar;
        }
        int defaultMinCptVer = ValParser.getAsInt(cfg, "minCptVer"); //最小可兼容版本
        List<String> DEFAULT_LIST = ValParser.getAsStrList(cfg, "defaultServerInclude");
        List<String> DEFAULT_BASEVERS = new ArrayList<>();
        DEFAULT_BASEVERS.add("0");//默认从0版本升级
        
        String dicPath = ValParser.getAsStr(cfg, "spliter");
        System.out.println("load default dictionary from " + dicPath);
        try {
            StringSpliter.init(dicPath, 2);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        List<Object> serviceList = ValParser.getAsList(cfg, "services");
        for(Object o : serviceList) {
            Map<String, Object> prj = ValParser.parseObject(o);
            int id = ValParser.getAsInt(prj, "id");
            String name = ValParser.getAsStr(prj, "name");
            String serviceDir = ServiceInfo.serviceHome(name);
            File serviceCfgFile = new File(FileUtil.addPath(serviceDir, SERVICE_CONFIG_FILE));
            if(!serviceCfgFile.exists()) {//services下不存在，可能在services_root下面
                serviceDir = ServiceInfo.serviceHome(name, 1);
                serviceCfgFile = new File(FileUtil.addPath(serviceDir, SERVICE_CONFIG_FILE));
                if(!serviceCfgFile.exists()) { //服务目录没有service.cfg文件，则放弃
                    System.out.println("Invalid project " + name + ",no " + SERVICE_CONFIG_FILE);
                    continue;
                }
            }

            String cmt = ValParser.getAsStr(prj, "cmt").replace("'", "''");
            List<String> serverIncludes = ValParser.getAsStrList(prj, "server_include");
            if(serverIncludes == null || serverIncludes.size() == 0) {
                serverIncludes = DEFAULT_LIST;
            }
            Set<String> clientExcludes = ValParser.getAsStrSet(prj, "client_exclude");
            if(clientExcludes == null) {
                clientExcludes = new HashSet<>();
            }
            
            List<String> baseVers = ValParser.getAsStrList(prj, "baseVers");
            if(baseVers == null) {
                baseVers = DEFAULT_BASEVERS;
            }
            int minCptVer = ValParser.getAsInt(prj, "minCptVer", defaultMinCptVer);
            ServiceInfo si = ServiceInfo.parse(serviceDir, name, null, true);
            services.put(si.name, new ServiceCfg(id, minCptVer, si, cmt, serverIncludes, clientExcludes, baseVers));
        }
        
        //以下三个服务，即使依赖，也不用申明，所以需要单独处理
        BIOS = services.get(SERVICE_BIOS);
        OAUTH = services.get(SERVICE_OAUTH2);
        WEBDB = services.get(SERVICE_WEBDB);

        ServiceInfo si = null;
        try {
            for(Map.Entry<String, ServiceCfg> o : services.entrySet()) {
                ServiceCfg sc = o.getValue();
                si = sc.si;
                for(Dependency dp : si.dependencies) {
                    ServiceCfg dpSc = services.get(dp.name);
                    if(dpSc != null) {
                        dpSc.dependTimes++;
                    }
                }
                
                if(si.hasDb) {
                    WEBDB.dependTimes++;
                }
                
                if(si.hasApi && !si.name.equals(SERVICE_BIOS) && !si.name.equals(SERVICE_OAUTH2)) {
                    OAUTH.dependTimes++;
                    BIOS.dependTimes++;
                }
            }
        } catch(Exception e) {
            System.out.println("Fail to parse service " + (si != null ? si.name : "unknown"));
            e.printStackTrace();
            return false;
        }
        
        int no = 0;
        for(Map.Entry<String, ServiceCfg> o : services.entrySet()) {
            ServiceCfg sc = o.getValue();
            if(allowedServices != null //命令行设置了需要重新生成的服务列表
               && !StringUtil.isInArray(sc.si.name, allowedServices)) {
                continue;
            }
            compressServer(sc, no);
            no++;
        }
        String s = insertServiceSqls.toString();
        System.out.println("\n-- insertServiceSqls:\n" + s + ";");
        s = updateServiceSqls.toString();
        System.out.println("\n-- updateServiceSqls:\n" + s);

        s = insertVerSqls.toString();
        System.out.println("\n-- insertVerSqls:\n" + s + ";");
        s = updateVerSqls.toString();
        System.out.println("\n-- updateVerSqls:\n" + s);
 
        s = insertSearchSqls.toString();
        System.out.println("\n-- insertSearchSqls:\n" + s + ";");
        
        s = insertDependencySqls.toString();
        System.out.println("\n-- insertDependencySqls:\n" + s.substring(0, s.length() - 2) + ";");//去除末尾的逗号
        
        s = insertRuleSqls.toString();
        System.out.println("\n-- insertRuleSqls:\n" + s + ";");
        s = updateRuleSqls.toString();
        System.out.println(s);
        
        return true;
    }

    /**
     * 压缩服务端安装包，包括UI与接口定义
     * @param no 计数
     * @return 压缩文件数量
     */
    private int compressServer(ServiceCfg serviceCfg, int no) {
        ServiceInfo si = serviceCfg.si;
        int n = 0;
        long lastest = System.currentTimeMillis();
        String dst = FileUtil.addPath(dstRoot, si.name);
        String packagePath = FileUtil.addPath(dst, si.version());
        File serverZip = new File(FileUtil.addPath(packagePath, SERVICE_SERVER_ZIP));
        if(si.type == ServiceType.COMMON || si.type == ServiceType.COMPANY) {
            lastest = serverZip.exists() ? serverZip.lastModified() : 0;
            if(lastest == 0 || updated(serviceCfg, lastest)) {
                File iconFile = new File(FileUtil.addPath(si.homeDir, SERVICE_FILE_DIR, FAVICON_FILE));
                FileUtil.createDir(dst);
                File dstFile = new File(FileUtil.addPath(dst, FAVICON_FILE));
                if(iconFile.exists()) {
                    FileUtil.copyFile(iconFile, dstFile);
                }
                FileUtil.createDir(packagePath); //只有需要生成server.zip的才创建目录

                n = compressServer(serviceCfg, serverZip);
                lastest = System.currentTimeMillis();
            }
        }
        
        //service,ui,type,level,ver,author,displayName,cmt,recentUpd,update_time,id
        //(%d,'%s','%s',%d,%d,%d,'%s','%s','%s',%d,%d)
        Object[] vals = new Object[] {
                si.name,
                si.clientType == ClientType.CLIENTUI ? "Y" : "N",
                si.type.name(),
                si.level,
                serviceCfg.dependTimes,
                si.version,
                si.author,
                si.displayName,
                serviceCfg.cmt,
                lastest,
                lastest,
                serviceCfg.id 
        };
        String line = String.format(INSERT_SERVICE_VALUES, vals);
        if(no > 0) {
            insertServiceSqls.append(','); //不是第一个，则需要加一个逗号
        }
        insertServiceSqls.append(line);
        line = String.format(UPDATE_SERVICE_SQL, vals);
        updateServiceSqls.append(line);
        
        if(serverZip.exists()) {
            //service,ver,minCVer,size,digest,cmt,update_time
            if(updateVerSqls.length() > 0) {
                insertVerSqls.append(",\n");
            }
            String digest = FileUtil.digest(serverZip);
            long size = serverZip.length();
            insertVerSqls.append("(").append(serviceCfg.id)
                .append(',').append(serviceCfg.si.version)
                .append(',').append(serviceCfg.minCptVer)
                .append(',').append(size)
                .append(",'").append(digest)
                .append("','").append(serviceCfg.cmt)
                .append("',").append(lastest)
                .append(')');
    
            updateVerSqls.append(String.format(UPDATE_VER_SQL, new Object[] {
                serviceCfg.minCptVer, size, digest, serviceCfg.cmt,
                lastest, serviceCfg.id, serviceCfg.si.version
            }));
        }
        
        List<String> ss = StringSpliter.listWords(serviceCfg.cmt);
        String summary = StringUtil.joinArray(ss, " ");
        //cls,did,title,summary,content,update_time
        line = String.format(INSERT_SEARCH_VALUES, new Object[] {
            si.type == ServiceType.PERSONAL || si.type == ServiceType.CLOUD ? "personal" : "enterprise",
            serviceCfg.id,
            si.name,
            summary,
            IConst.EMPTY_STR,
            lastest
        });
        if(no > 0) {
            insertSearchSqls.append(',');
        }
        insertSearchSqls.append(line);
        
        for(String baseVer : serviceCfg.baseVers) {
            vals = new Object[] {
                serviceCfg.si.version,
                lastest,
                serviceCfg.id,
                baseVer
            };
            
            line = String.format(INSERT_RULE_VALUES, vals);
            if(insertRuleSqls.length() > INSERT_RULE_SQL.length()) {
                insertRuleSqls.append(','); //不是第一个，则需要加一个逗号
            }
            insertRuleSqls.append(line);
            line = String.format(UPDATE_RULE_SQL, vals);
            updateRuleSqls.append(line);
        }
        
        //service(%d),dependency(%d),ver,minCVer,features,update_time
        List<Dependency> dps = new ArrayList<>();
        for(Dependency dp : si.dependencies) {
            dps.add(dp);
        }
        
        if(si.hasDb) {
            dps.add(new Dependency(WEBDB.si.name, WEBDB.si.version(), "*", false));
        }
        
        if(si.hasApi && !si.name.equals(SERVICE_BIOS) && !si.name.equals(SERVICE_OAUTH2)) {
            dps.add(new Dependency(OAUTH.si.name, OAUTH.si.version(), "*", false));
            dps.add(new Dependency(BIOS.si.name, BIOS.si.version(), "*", false));
        }

        for(Dependency dp : dps) {
            ServiceCfg dpSc = services.get(dp.name);
            if(dpSc == null || dpSc.id == serviceCfg.id) {
                continue; //不依赖自己
            }
            line = String.format(INSERT_DEPENDENCY_SEGS,
                    serviceCfg.id,
                    serviceCfg.si.version,
                    dpSc.id,
                    dpSc.si.version,
                    dp.features,
                    lastest);
            insertDependencySqls.append(line);
        }
        return n;
    }
    
    private boolean updated(ServiceCfg serviceCfg, long lastest) {
        ServiceInfo si = serviceCfg.si;
        for(String l : serviceCfg.serverIncludes) {
            File f = new File(FileUtil.addPath(si.homeDir, l));
            if(!f.exists()) {
                continue; //并不是每个目录都有完整的子目录、文件
            }
            
            if(updated(f, lastest)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean updated(File f, long lastest) {
        File[] list = f.listFiles();
        if(list == null || list.length == 0) {
            return false;
        }
        for(File l : list) {
            if(l.isDirectory()) {
                if(updated(l, lastest)) {
                    return true;
                }
            } else if(l.lastModified() > lastest){
                return true;
            }
        }
        return false;
    }
    
    private int compressServer(ServiceCfg serviceCfg, File serverZip) {
        int n = 0;
        ServiceInfo si = serviceCfg.si;
        FileUtil.remove(serverZip);
        System.out.println("Compress server:" + si.homeDir + ",versio:" + si.version());
        Set<String> excludes = new HashSet<>();
        excludes.addAll(ServerExcludes); //公共的、不必压缩的文件
        for(String s : serviceCfg.clientExcludes) {
            excludes.add(IConst.SERVICE_FILE_DIR + "/" + s); //从服务根目录压缩，所以需要加上file
        }

        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(serverZip))) {
            FileUtil.zipOneFile(si.homeDir, new File(FileUtil.addPath(si.homeDir, SERVICE_CONFIG_FILE)), zos);
            for(String l : serviceCfg.serverIncludes) {
                File f = new File(FileUtil.addPath(si.homeDir, l));
                if(!f.exists()) {
                    continue; //并不是每个目录都有完整的子目录、文件
                }
                
                if(f.isDirectory()) {
                    n += FileUtil.zipOneDir(si.homeDir, f, zos, excludes);
                } else {
                    FileUtil.zipOneFile(si.homeDir, f, zos);
                    n++;
                }
            }
        } catch (IOException e) {
            System.out.println("Fail to zip files under " + srcRoot + " to " + serverZip);
            e.printStackTrace();
        }
        return n;
    }
    /**
     * 压缩服务目录下file子目录中的所有文件，并添加app.cfg文件，
     * 此文件是提供给客户端的，是客户端的UI。
     */
//    private int compressClient(ServiceCfg sc, File clientZip) {
//        ServiceInfo si = sc.si;
//        System.out.println("Compress client:" + si.homeDir);
//        File srcZip = new File(FileUtil.addPath(AbsPlatform.clientsRoot(), si.name + ".zip"));
//        int n = si.compressClient(sc.clientExcludes, srcZip);
//        if(n > 0) {
//            FileUtil.copyFile(srcZip, clientZip);
//        }
//        return n;
//    }
    
//    /**
//     * 压缩服务包，包括客户端与服务端zip，以及介绍
//     */
//    private int compressService(String root, File serviceZip) {
//        System.out.println("Compress service:" + root);
//        FileUtil.remove(serviceZip);
//        Set<String> excludes = new HashSet<>();
//        excludes.add(SERVICE);
//        return FileUtil.zipDir(serviceZip, root, excludes);
//    }

    @Override
    public String[] help() {
        return new String[]{
            "release services defined in config file.",
            "renerate server.zip for each service.",
            name + "[\"service1,service2,...\"] [path_to_config_file]"
        };
    }
    
    private static class ServiceCfg {
        final int id;
        final String cmt;
        final int minCptVer; //最小可兼容版本
        final List<String> serverIncludes;
        final List<String> baseVers; //可以升级到此版本的基础版本
        final Set<String> clientExcludes;
        final ServiceInfo si;
        int dependTimes = 0;
        
        ServiceCfg(int id, int minCptVer, ServiceInfo si, String cmt, List<String> serverIncludes,
                Set<String> clientExcludes, List<String> baseVers) {
            this.id = id;
            this.minCptVer = minCptVer;
            this.si = si;
            this.cmt = cmt;
            this.serverIncludes = serverIncludes;
            this.clientExcludes = clientExcludes;
            this.baseVers = baseVers;
        }
    }
}
