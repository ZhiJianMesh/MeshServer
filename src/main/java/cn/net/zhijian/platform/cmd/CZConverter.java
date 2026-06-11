package cn.net.zhijian.platform.cmd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IConst;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.IP2Addr;
import cn.net.zhijian.util.StringSpliter;
import cn.net.zhijian.util.StringUtil;

/**
 * 将IP地址库转为数据库记录
 */
public class CZConverter extends AbsCommand {
    private static final byte VERSION = 0;
    
    private static final String WORKDIR = System.getProperty("user.dir");
    private static final String CZCFG_DIR = "czconvert";
    
    private static final int MAX_SEG_LEN = 0xffffff;
    private static final int MAX_ADDR_DEPTH = 4;
    
    //china，组织国家信息时，中国必须放在第二个，第一个是未知
    private static final int NULL_COUNTRY_IDX = 0;
    private static final int NULL_PROVINCE_IDX = 0;
    private static final int CHINA_COUNTRY_IDX = 1;

    private static final String[] DEPARTMENTS = {"大学", "公司", "学院", "实验室", "分校"};
    private static final String[] INVALIDKEYS = {"节点", "网络", "网吧", "机房", "网咖", "出口"};
    
    //CountryTab、ProvinceTab、TelcomTab都有别名，可能多对一，所以必须用一个数组记录
    private static final List<CfgEle> Countries = new ArrayList<>();
    private static final Map<String, CfgEle> CountryMap = new HashMap<>();
    private static final List<CfgEle> Provinces = new ArrayList<>();
    private static final Map<String, CfgEle> ProvinceMap = new HashMap<>();
    private static final List<CfgEle> Telcoms = new ArrayList<>();
    private static final Map<String, CfgEle> TelcomMap = new HashMap<>();
    private static final Map<String, String[]> AdjustMap = new HashMap<>();

    //记录地址信息，多个ip段可能对应一个地址信息，平均达到10:1，以共用方式减少内存消耗
    private static final Map<String, AddrEle> AddrMap = new LinkedHashMap<>();
    //IP段，每一项有开始、长度、地址段编号
    private static final List<IndexEle> IndexMap = new ArrayList<>();

    public CZConverter(String name) {
        super(name);
    }
    
    @Override
    public String[] help() {
        return new String[]{"convert CZ ip-address data to local format",
                name + " [showitems(true|false)] [[path_to_iptxt] path_to_dictionary]"};
    }

    @Override
    public boolean run(String[] args) throws Exception {
        int showItemIdx = StringUtil.indexOf(args, "showitems");
        boolean showItems = false;
        String[] params = args;
        if(showItemIdx >= 0) {
            params = StringUtil.removeEle(params, showItemIdx);
            showItems = true;
        }
        
        String ipSrcFile;
        if(params.length > 0) {
            ipSrcFile = params[0];
        } else {
            ipSrcFile = FileUtil.addPath(WORKDIR, "ip.txt");
        }
        
        if(!new File(ipSrcFile).exists()) {
            printHelp(help());
            System.out.println("`" + params[0] + "` not exists");
            return false;
        }
        
        String dictionaryFile = IConst.EMPTY_STR;
        if(params.length > 1) {
            dictionaryFile = params[1];
        } else {
            dictionaryFile = FileUtil.addPath(ServiceInfo.servicesRoot(), IConst.SERVICE_WEBDB, "dictionary.txt");
        }
        
        int nCountries = loadCfgEles("countries.txt", CountryMap, Countries, false);
        if(nCountries < 0 || nCountries > 255) {
            System.out.println("No country config or too many countries");
            return false;
        }
        
        int nProvinces = loadCfgEles("provinces.txt", ProvinceMap, Provinces, false);
        if(nProvinces < 0 || nProvinces > 255) {
            System.out.println("No province config or too many provinces");
            return false;
        }

        int nTelComs = loadCfgEles("telcoms.txt", TelcomMap, Telcoms, true);
        if(nTelComs <= 0 || nTelComs > 255) {
            System.out.println("No telcom config or too many telcoms");
            return false;
        }
        
        //部分不规范的位置标识，将它调整为合适的格式，只调整顶级行政单位
        int nAdjusts = loadAdjusts("adjusts.txt", AdjustMap);
        if(nAdjusts < 0) {
            return false;
        }

        List<IPSeg> segs = loadSrc(ipSrcFile);
        if(segs == null) {
            System.out.println("There is no valid data in " + ipSrcFile);
            return false;
        }
        
        int n = segs.size();
        System.out.println("0:" + segs.get(0).start
                + ",1:" + segs.get(1).start
                + ",n-2:" + segs.get(n - 2).start
                + ",n-1:" + segs.get(n - 1).start);

        System.out.println(
            String.format("Countries:%d,Provinces:%d,TelComs:%d",
                    nCountries, nProvinces, nTelComs)
        );
        
        //加载分词库
        try(InputStream in = new FileInputStream(dictionaryFile);
            BufferedInputStream bis = new BufferedInputStream(in)) {
            StringSpliter.init(bis, 3);
            StringSpliter.addPunctuations(new char[]{'省', '市', '县', '村'});
        } catch(Exception e) {
            printException("Fail to init spliter", e);
            return false;
        }
        
        int p;
        List<String> words;
        String descr, detail;
        int telcom, country, province;
        int start, m, count;
        CfgEle tce, cGov, pGov;
        String first, word;
        String[] adjust;
        AddrEle addrEle;
        List<String> unknownCountries = new ArrayList<>();
        
        try {
            for(IPSeg s : segs) {
                p = s.descr.indexOf('/'); //后面是具体的位置信息，比如大学、医院等
                if(p > 0) {
                    detail = s.descr.substring(p + 1);
                    if(StringUtil.contains(detail, INVALIDKEYS)) {
                        detail = null;
                    } else {
                        words = StringSpliter.splitByPunctuations(detail);
                        detail = IConst.EMPTY_STR;
                        for(String w : words) {
                            if(detail.length() > 30) {
                                break;
                            }
                            detail += w;
                        }
                    }
                    descr = s.descr.substring(0, p);
                } else {
                    detail = null;
                    descr = s.descr;
                }
                words = StringSpliter.listWords(descr); //先经过分词，然后再寻找地址
               
                start = 1;//第一段是国家名或省份名，都不用记录，所以跳过
                country = NULL_COUNTRY_IDX; //无效
                province = NULL_PROVINCE_IDX; //无效省份
                telcom = 0; //未知，CZ88或者无运营商信息
                first = words.size() > 0 ? words.get(0) : "*";
                
                adjust = AdjustMap.get(first);
                if(adjust != null) {
                    for(int i = adjust.length - 1; i >= 0; i--) {
                        words.add(0, adjust[i]);
                    }
                    first = adjust[0];
                }
                
                cGov = CountryMap.get(first);
                if(cGov != null) { //第一个是国家名，则不记录省份
                    country = cGov.idx;
                    cGov.hits++;
                    if(words.size() > 1) { //第二段是省份
                        if((pGov = ProvinceMap.get(words.get(1))) != null) {
                            pGov.hits++;
                            province = pGov.idx;
                            start++;
                        }
                    }
                } else if((pGov = ProvinceMap.get(first)) != null){ //第一段是中国省份
                    country = CHINA_COUNTRY_IDX;
                    pGov.hits++;
                    province = pGov.idx;
                    Countries.get(country).hits++;
                } else {
                    start = 0;
                    unknownCountries.add(first + "|" + descr);
                    Countries.get(country).hits++;
                }
                
                for(n = start; n < words.size(); n++) { //寻找运营商字段
                    tce = TelcomMap.get(words.get(n).toLowerCase());
                    if(tce != null) { //出现了列表中的运营商，则抛弃后面的内容
                        telcom = tce.idx;
                        tce.hits++;
                        break;
                    }
                }
                
                n = n > MAX_ADDR_DEPTH ? MAX_ADDR_DEPTH: n;
                descr = IConst.EMPTY_STR;
                count = 0;
                for(m = start; m < n && count < 3; m++) {
                    word = words.get(m);
                    if(!StringUtil.isInArray(word, DEPARTMENTS)) {
                        if(count > 0) {
                            descr += " ";
                        }
                        count++;
                    }
                    descr += words.get(m);
                }
                if(detail != null) {
                    descr += " " + detail;
                }
                addrEle = AddrMap.get(descr); //只要相同，就可以复用
                if(addrEle == null) {
                    addrEle = new AddrEle(descr);
                    AddrMap.put(descr, addrEle);
                } else {
                    addrEle.hits++;
                }
                IndexMap.add(new IndexEle(s.start, s.len, addrEle.idx, country, province, telcom));
            }
        } catch (Exception e) {
            printException("Fail to parse src data", e);
            return false;
        }

        n = 0;
        IndexEle ie1, ie2;
        for(int i = IndexMap.size() - 1; i > 0; i--) {
            ie2 = IndexMap.get(i);
            ie1 = IndexMap.get(i - 1);
            if(ie1.isCloseArea(ie2)) { //合并相邻的且同区域的索引
                ie1.len = ie2.start + ie2.len - ie1.start;
                if(ie1.len <= MAX_SEG_LEN) {
                    IndexMap.remove(i); //删除当前，分段太长也不可以合并
                }
                n++;
            }
        }
        System.out.println("Number of merged close areas:" + n);
        
        saveData(showItems);
        try(BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(FileUtil.addPath(WORKDIR, "unknownCountries.txt")));
            DataOutputStream dos = new DataOutputStream(bos)) {
            for(String s : unknownCountries) {
                dos.write(s.getBytes(StandardCharsets.UTF_8));
                dos.write('\n');
            }
        } catch (Exception e) {
            printException("Fail to write unknownCountries", e);
        }
        System.out.println("Number of unknown countries:" + unknownCountries.size());

        return true;
    }
    
    /**
     * 读取IP分段及其地址，将IP地址转为整型值
     * 读完之后按start从小到大排序，合并太小的分段
     * @param file 文件
     * @return 排序后的列表
     * @throws IOException 文件读异常
     */
    private static List<IPSeg> loadSrc(String file) {
        String line = IConst.EMPTY_STR;
        int p1;
        int p2;
        int ip1, ip2;
        int len;
        String start, end;
        String descr;
        IPSeg seg, seg1;
        List<IPSeg> segs = new ArrayList<>();
        int n = 0;
        int lineNo = 1;
        
        try(BufferedReader br = new BufferedReader(new FileReader(file))) {
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if(line.length() == 0) {
                    continue;
                }
                p1 = line.indexOf(' ');
                start = line.substring(0, p1);
                while(line.charAt(p1) == ' ') {
                    p1++;
                }
                
                p2 = line.indexOf(' ', p1);
                end = line.substring(p1, p2);
                while(line.charAt(p2) == ' ') {
                    p2++;
                }
                ip1 = IP2Addr.ipv4ToInt(start);
                ip2 = IP2Addr.ipv4ToInt(end);
                descr = line.substring(p2).trim();
                seg = new IPSeg(ip1, ip2, descr);
                if(seg.len <= MAX_SEG_LEN) {
                    segs.add(seg);
                    continue;
                }
                
                //分裂超过MAX_SEG_LEN的分段
                //之所以采用分裂，而不是将seg扩大1字节，一是节省400k，一是8字节正好对齐
                len = seg.len;
                seg.len = MAX_SEG_LEN; //调整长度
                segs.add(seg);
                
                ip1 = Math.min(ip1,  ip2) + MAX_SEG_LEN + 1;
                do {
                    len -= MAX_SEG_LEN;
                    ip2 = ip1 + (len > MAX_SEG_LEN ? MAX_SEG_LEN : len);
                    seg1 = new IPSeg(ip1, ip2, descr);
                    segs.add(seg1);
                    ip1 = ip2 + 1;
                    n++;
                } while(len > MAX_SEG_LEN);
            }
        } catch(Exception e) {
            System.err.println(String.format("error line `%s` @%d", line, lineNo));
            printException(String.format("Fail to load segments from `{}`", file), e);
            return null;
        }
        System.out.println("Number of expended too long segments:" + n);
        
        segs.sort((IPSeg o1, IPSeg o2) -> {
            //必须使用Integer.compare，否则不起作用
            return Integer.compare(o1.start, o2.start);
        });
        
        len = segs.size();
        n = 0;
        //合并太小的分段
        for(int i = segs.size() - 1; i > 0; i--) {
            seg = segs.get(i);
            if(seg.len > 2) { 
                continue;
            }
            seg1 = segs.get(i - 1);
            len = seg.start + seg.len - seg1.start;
            if(len > 255) { //相距太远，不合并
                continue;
            }
            seg1.len = len;
            segs.remove(i);
            n++;
        }

        //便于二分查找，减少边界判断，额外插入最小与最大值
        segs.add(0, new IPSeg(Integer.MIN_VALUE, Integer.MIN_VALUE, "*"));
        segs.add(new IPSeg(Integer.MAX_VALUE, Integer.MAX_VALUE, "*"));
        System.out.println("Number of merged segments:" + n);
        
        return segs;        
    }
    
    private static boolean saveData(boolean showItems) {
        byte[] addrBuf = getStrBuf("Addr", AddrMap.values(), false);
        byte[] telcomBuf = getStrBuf("Telcom", Telcoms, showItems);
        byte[] countryBuf = getStrBuf("Country", Countries, showItems);
        byte[] provinceBuf = getStrBuf("Province", Provinces, showItems);
        byte[] buf = new byte[8];
        
        try(BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(FileUtil.addPath(WORKDIR, "ip.dat")));
            DataOutputStream dos = new DataOutputStream(bos)) {
            dos.write(VERSION);
            dos.writeInt((int)(System.currentTimeMillis() / 60000)); //UTC分钟
            dos.writeInt(countryBuf.length);
            dos.writeInt(provinceBuf.length);
            dos.writeInt(telcomBuf.length);
            dos.writeInt(addrBuf.length);
            dos.write(countryBuf);
            dos.write(provinceBuf);
            dos.write(telcomBuf);
            dos.write(addrBuf);
            
            for(IndexEle ie : IndexMap) {
                dos.writeInt(ie.start); //每个单元都是定长的，方便二分查找
                encodeInt(buf, ie.len, 0, 3);
                encodeInt(buf, ie.addrIdx, 3, 2);
                buf[5] = (byte)ie.country;
                buf[6] = (byte)ie.province;
                buf[7] = (byte)ie.telcom;
                dos.write(buf);
            }
        } catch (Exception e) {
            printException("Fail to save ip.dat", e);
            return false;
        }
        
        int indexLen = IndexMap.size() * 12;
        System.out.println("Countries)num:" + Countries.size() + ",length:" + countryBuf.length
                + "\nProvinces)num:" + Provinces.size() + ",length:" + provinceBuf.length
                + "\nTelcoms)num:" + Telcoms.size() + ",length:" + telcomBuf.length
                + "\nAddress)num:" + AddrMap.size() + ",length:" + addrBuf.length
                + "\nIndex)num:" + IndexMap.size() + ",length:" + indexLen);
        int total = 21 + indexLen + addrBuf.length + countryBuf.length
                    + provinceBuf.length + telcomBuf.length;
        System.out.println(String.format("total length:%d", total));
        return true;        
    }
    
    private static byte[] getStrBuf(String name, Collection<? extends AbsStrEle> list, boolean showItem) {
        byte[] buf;
        int len;
        String s;
        int hits = 0;
        byte[] content;
        
        if(showItem) {
            System.out.println("Items of " + name);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(100000)) {
            for(AbsStrEle se : list) {
                s = se.get();
                buf = s.getBytes(StandardCharsets.UTF_8);
                len = buf.length;
                if(len > 0xff) {
                    System.err.println(String.format(name + " `%s` too long", s));
                    len &= 0xff; //最大255字节，超过则截断
                }
                baos.write(len);
                baos.write(buf);
                hits += se.hits;
                if(showItem) {
                    System.out.println(se.get() + ")idx:" + se.idx + ",hits:" + se.hits);
                }
            }
            content = baos.toByteArray();
        } catch (Exception e) {
            printException("Fail to save " + name, e);
            content = new byte[] {};
        }
        System.out.println(name + ") hits:" + hits + ",num:" + list.size() + ",length:" + content.length);
        return content;
    }
    
    private static void printException(String info, Throwable e) {
        System.err.println(info);
        e.printStackTrace();
    }
    
    private static void encodeInt(byte[] buff, int v, int pos, int len) {
        for (int i = len - 1; i >= 0; i--) {
            buff[pos + i] = (byte) (v & 0xff);
            v >>= 8;
        }
    }
    
    private static int loadCfgEles(String cfgName, Map<String, CfgEle> map, List<CfgEle> list, boolean lowerCase) {
        int idx = 0;
        int pos = 0;
        int num = 0;
        String line;
        String name, nick;
        CfgEle cfgEle;
        Map<String, CfgEle> nicks = new HashMap<String, CfgEle>();
        
        String fullName = FileUtil.addPath(WORKDIR, IConst.SYS_CONF_DIR, CZCFG_DIR, cfgName);
        
        try(InputStream in = new FileInputStream(fullName);
            BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if(IConst.EMPTY_STR.equals(line) || line.startsWith("//")) {
                    continue;
                }
                
                pos = line.indexOf('-');
                if(pos > 0) {
                    name = line.substring(0, pos).trim();
                    nick = line.substring(pos + 1).trim();
                } else {
                    name = line;
                    nick = line;
                }
                
                cfgEle = nicks.get(nick);
                if(cfgEle == null) {
                    cfgEle = new CfgEle(idx++, nick);
                    nicks.put(nick, cfgEle);
                    num++;
                    list.add(cfgEle);
                }
                if(lowerCase) {
                    map.put(name.toLowerCase(), cfgEle);
                } else {
                    map.put(name, cfgEle);
                }
            }
            return num;
        } catch(Exception e) {
            printException("Fail to load addrs", e);
            return -1;
        }
    }
    
    private static int loadAdjusts(String cfgName, Map<String, String[]> cache) {
        int pos = 0;
        int num = 0;
        String line;
        String name, to;
        String fullName = FileUtil.addPath(WORKDIR, IConst.SYS_CONF_DIR, CZCFG_DIR, cfgName);
        
        try(InputStream in = new FileInputStream(fullName);
            BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if(IConst.EMPTY_STR.equals(line) || line.startsWith("//")) {
                    continue;
                }
                
                pos = line.indexOf('-');
                if(pos <= 0) {
                    continue;
                }
                name = line.substring(0, pos).trim();
                to = line.substring(pos + 1).trim();
 
                cache.put(name, to.split(" "));
            }
            return num;
        } catch(Exception e) {
            printException("Fail to load adjusts", e);
            return -1;
        }
    }
    
    private static class IndexEle {
        int start;
        //segLen:2|addrIdx:2|country:1|province:1|telcom:1
        int len;
        final int addrIdx;
        final int country;
        final int province;
        final int telcom;
        
        IndexEle(int start, int segLen, int addrIdx,
                 int country, int province, int telcom) {
            this.start = start;
            this.len = segLen;
            this.addrIdx = addrIdx;
            this.country = country;
            this.province = province;
            this.telcom = telcom;
        }
        
        boolean isCloseArea(IndexEle other) {
            if(this.addrIdx == other.addrIdx
               && this.country == other.country
               && this.province == other.province
               && this.telcom == other.telcom) {
                if(this.start > other.start) {
                   return Math.abs(this.start - (other.start + other.len)) < 5; 
                }
                
                return Math.abs(other.start - (this.start + this.len)) < 5; 
            }
            return false;
        }
    }
    
    private static class IPSeg {
        int start;
        int len;
        final String descr;
        
        IPSeg(int ip1, int ip2, String descr) {
            if(ip1 < ip2) {
                this.start = ip1;
                this.len = ip2 - ip1;
            } else {
                this.start = ip2;
                this.len = ip1 - ip2;
            }
            this.descr = descr;
        }
    }
    
    private abstract static class AbsStrEle {
        int hits;
        final int idx;
        abstract String get();
        
        AbsStrEle(int idx) {
            this.idx = idx;
        }
    }
    
    private static class CfgEle extends AbsStrEle {
        final String nick;
        
        CfgEle(int idx, String nick) {
            super(idx);
            this.nick = nick;
        }
       
        @Override
        public String get() {
            return nick;
        }
    }
    
    private static class AddrEle extends AbsStrEle {
        static int no = 0; //不用在多线程中，所以不用原子类
        final String addr;
        
        AddrEle(String addr) {
            super(no++);
            this.addr = addr;
        }
        
        @Override
        public String get() {
            return addr;
        }
    }
}
