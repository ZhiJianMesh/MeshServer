package cn.net.zhijian.platform.cmd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.security.Provider;
import java.security.Security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import cn.net.zhijian.util.BouncyBase;
import cn.net.zhijian.util.Ecc;
import cn.net.zhijian.util.FileUtil;
import cn.net.zhijian.util.SecureUtil;
import cn.net.zhijian.util.StringUtil;

public class Cert extends AbsCommand {
    private static final String KS_TYPE_BKS = "BKS";
    private static final String KS_TYPE_JKS = "JKS";
    private static final String KS_ALIAS = "MeshCA";
    
    private static final Provider BC_PROVIDER = BouncyBase.PROVIDER;
    private static final String SUN_PROVIDER = "SUN";
    private static final String SIGN_ALGORITHM = Ecc.CA_SIGN_ALGORITHM;
    
    private static final String workDir = System.getProperty("user.dir");
    
    private static final char[] UPPER_HEX_CHARS = { 
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

    public Cert(String name) {
        super(name);
    }

    @Override
    public boolean run(String[] args) throws Exception {
        int idx;
        String[] params = args;
        boolean result = false;
        
        if((idx = StringUtil.indexOf(params, "createroot")) >= 0) {
            params = StringUtil.removeEle(params, idx);
            result = createRoot(params);
        } else if((idx = StringUtil.indexOf(params, "createsub")) >= 0) {
            params = StringUtil.removeEle(params, idx);
            result = createSub(params);
        } else if((idx = StringUtil.indexOf(params, "list")) >= 0) {
            params = StringUtil.removeEle(params, idx);
            result = list(params);
        }

        if(!result) {
            printHelp(help());
        }
        
        return true;
    }

    @Override
    public String[] help() {
        return new String[]{
            name + ",manage self-signed certificates",
            "1)createroot [jks] path_to_cert_file password valid_days name",  
            "  name example:\"CN=ZhiJian Root CA,C=CN,OU=zhijian.net.cn,O=ZhiJianNetCN\"",
            "2)createsub path_to_parentcert_file parentpwd path_to_cert_file password valid_days name",
            "  name example:\"CN=ZhiJian Mesh CA,C=CN,OU=zhijian.net.cn,O=ZhiJianNetCN\"",
            "3)list path_to_cert_file password"
        };
    }
    
    private boolean createRoot(String[] args) throws Exception {
        if(args.length < 4) {
            return false; //dest_file,pwd,days,host_name
        }
        String file = args[0];
        char[] pwd = args[1].toCharArray();
        int days = Integer.parseInt(args[2]);
        X500Name name = new X500Name(args[3]); //根证书，issuer与subject相同
        
        Date validAt = new Date();
        Date expiresAt = new Date(validAt.getTime() + 86400000L * days);

        // Create self signed Root CA certificate
        KeyPair rootKeyPair = Ecc.genCertKeyPair();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, // issuer
                BigInteger.valueOf(System.currentTimeMillis()), // serial number of certificate
                validAt, // start of validity
                expiresAt, // end of certificate validity
                name, // subject name of certificate
                rootKeyPair.getPublic()); // public key of certificate
        // key usage restrictions
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(true));

        ContentSigner signGen = new JcaContentSignerBuilder(SIGN_ALGORITHM)
                .setProvider(BC_PROVIDER)
                .build(rootKeyPair.getPrivate());//根证书，自签名，用自己的私钥
        
        // 将证书换为X.509证书
        X509Certificate rootCA = new JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER)
                .getCertificate(builder.build(signGen));
        //证书，只携带公钥及证书链，用于导入浏览器
        saveToFile(rootCA, FileUtil.addPath(workDir, file + ".cer"));
        
        Certificate[] chain = new Certificate[] {rootCA};

        PrivateKey pk = rootKeyPair.getPrivate();
        //bks证书，包括公私钥，用于服务器
        saveToBks(file, KS_ALIAS, chain, pk, pwd);
        //jks证书，包括公私钥，用于服务器，当前都使用bks
        saveToJks(file, KS_ALIAS, chain, pk, pwd);
        
        return true;
    }

    private boolean createSub(String[] params) throws Exception {
        if(params.length < 6) {
            return false; //parent_cert,parent_pwd,sub_cert_file,sub_pwd,days,host_name
        }
        String[] args;
        String parentType;
        String ksType;
        Provider provider;
        if(params[0].equalsIgnoreCase(KS_TYPE_JKS)) {
            args = StringUtil.removeEle(params, 0);
            parentType = ".jks";
            ksType = KS_TYPE_JKS;
            provider = Security.getProvider(SUN_PROVIDER);
        } else {
            args = params;
            parentType = ".bks";
            ksType = KS_TYPE_BKS;
            provider = BC_PROVIDER;
        }
        
        String parent = args[0];
        char[] parentPwd = args[1].toCharArray();
        String sub = args[2];
        char[] subPwd = args[3].toCharArray();
        int days = Integer.parseInt(args[4]);
        X500Name name = new X500Name(args[5]);
        
        KeyStore parentKeyStore = KeyStore.getInstance(ksType, provider);
        String parentBksFile = FileUtil.addPath(workDir, parent + parentType);
        try (FileInputStream fis = new FileInputStream(parentBksFile)){
            parentKeyStore.load(fis, parentPwd);
        }
        
        PrivateKey parentPrivateKey = (PrivateKey)parentKeyStore.getKey(KS_ALIAS, parentPwd);
        Certificate[] parentCAs = parentKeyStore.getCertificateChain(KS_ALIAS);
        
        X509CertificateHolder holder = new X509CertificateHolder(parentCAs[0].getEncoded());
        X509Certificate parentCA = new JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER)
                .getCertificate(holder);
        
        Date validAt = new Date();
        Date expiresAt = new Date(validAt.getTime() + 86400000L * days);

        // Create self signed Root CA certificate
        KeyPair subKeyPair = Ecc.genCertKeyPair();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                parentCA, // issuer，签发人
                BigInteger.valueOf(System.currentTimeMillis()), // serial number of certificate
                validAt, // start of validity
                expiresAt, // end of certificate validity
                name, // subject name of certificate
                subKeyPair.getPublic()); // public key of certificate
        
        //服务器证书，不可限制使用范围，否则报illegal_parameter异常
//              builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
//              builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(true));
        ContentSigner signGen = new JcaContentSignerBuilder(SIGN_ALGORITHM)
                .setProvider(BC_PROVIDER)
                .build(parentPrivateKey);//下级证书用上级证书的私钥签名
        
        X509Certificate subCA = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signGen)); 
        //证书，只携带公钥及证书链，用于导入浏览器
        saveToFile(subCA, FileUtil.addPath(workDir, sub + ".cer"));
        
        Certificate[] chain = new Certificate[parentCAs.length + 1];
        System.arraycopy(parentCAs, 0, chain, 1, parentCAs.length);
        chain[0] = subCA;
        PrivateKey pk = subKeyPair.getPrivate();
        saveToBks(sub, KS_ALIAS, chain, pk, subPwd);
        saveToJks(sub, KS_ALIAS, chain, pk, subPwd);
        
        return true;
    }

    private boolean list(String[] args) throws Exception {
        if(args.length < 2) {
            return false;
        }
        String file = args[0];
        char[] pwd = args[1].toCharArray();
        
        KeyStore bks = KeyStore.getInstance(KS_TYPE_BKS, BC_PROVIDER);
        File bksFile = formFilePath(file);
        try (FileInputStream fis = new FileInputStream(bksFile)){
            bks.load(fis, pwd);
        }
        
        PrivateKey privateKey = (PrivateKey)bks.getKey(KS_ALIAS, pwd);
        System.out.println("PrivateKey:");
        printHex(privateKey.getEncoded(), "    ");
        System.out.println("\n--------------------------------------");
        
        int i = 1;
        Certificate[] chain = bks.getCertificateChain(KS_ALIAS);
        for(Certificate c : chain) {
            showCertificate(c, i++);
        }
        
        return true;
    }
    
    private void showCertificate(Certificate c, int no)
            throws IOException, CertificateException {
        X509CertificateHolder holder = new X509CertificateHolder(c.getEncoded());
        X509Certificate ca = new JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER)
                .getCertificate(holder);
        System.out.println("==== No." + no + " ====");
        System.out.println(ca.toString());
        byte[] sign = SecureUtil.sha1(ca.getEncoded());
        System.out.println("SHA1(public):" + toHex(sign));
        sign = SecureUtil.md5(ca.getEncoded());
        System.out.println("MD5(public):" + toHex(sign));
    }
    
    private File formFilePath(String input) {
        File f = new File(input);
        if(f.exists() && f.isFile()) {
            return f;
        }
        return new File(FileUtil.addPath(workDir, input));
    }
    /**
     * 将获取到得编码进行16 进制转换
     *
     * @param arr 二进制
     * @return 16进制字符串，以":"分隔
     */
    private static String toHex(byte[] arr) {
        StringBuilder str = new StringBuilder(arr.length * 3);
        int v;
        for (byte b : arr) {
            v = b & 0xff;
            if (str.length() > 0) {
                str.append(':');
            }
            str.append(UPPER_HEX_CHARS[v >> 4]).append(UPPER_HEX_CHARS[v & 0x0f]);
        }
        return str.toString();
    }
    
    private static void printHex(byte[] d, String head) {
        int i = 0;
        int v;
        
        for(byte b : d) {
            v = ((int)b) & 0xff;
            if((i % 16) == 0) {
                System.out.println();
                System.out.print(head);
            }
            i++;
            System.out.print(UPPER_HEX_CHARS[v >> 4]);
            System.out.print(UPPER_HEX_CHARS[v&0xf]);
        }
    }

    private static void saveToFile(X509Certificate certificate, String filePath)
            throws IOException, CertificateEncodingException {
        try(FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
            fileOutputStream.write(certificate.getEncoded());
        }
    }
    
    /**
     * 将证书存为bks格式
     * 在安卓应用签名时，不支持bks，但是安卓服务器的证书只支持bks
     * @param file 文件名
     * @param alias 密钥别名
     * @param chain 证书链
     * @param privateKey 私钥
     * @param pwd 证书密码
     * @throws Exception 异常
     */
    private static void saveToBks(String file, String alias,
            Certificate[] chain, PrivateKey privateKey, char[] pwd) throws Exception {
        String ksFile = FileUtil.addPath(workDir, file + ".bks");
        try(FileOutputStream fos = new FileOutputStream(ksFile)) {
            KeyStore bks = KeyStore.getInstance(KS_TYPE_BKS, BC_PROVIDER);
            bks.load(null, null);
            bks.setKeyEntry(alias, privateKey, pwd, chain);
            bks.store(fos, pwd);
        }
    }

    /**
     * 将证书存为jks格式
     * 在安卓应用签名时，不支持bks，但是安卓服务器的证书只支持bks
     * @param file 文件名
     * @param alias 密钥别名
     * @param chain 证书链
     * @param privateKey 私钥
     * @param pwd 证书密码
     * @throws Exception 异常
     */
    private static void saveToJks(String file, String alias,
            Certificate[] chain, PrivateKey privateKey, char[] pwd) throws Exception {
        String ksFile = FileUtil.addPath(workDir, file + ".jks");
        try(FileOutputStream fos = new FileOutputStream(ksFile)) {
            KeyStore jks = KeyStore.getInstance(KS_TYPE_JKS, SUN_PROVIDER);
            jks.load(null, null);
            jks.setKeyEntry(alias, privateKey, pwd, chain);
            jks.store(fos, pwd);
        }
    }
    
    /**
     * 产生256位ECC密钥对
     * @return 返回密钥对
     * @throws NoSuchAlgorithmException 算法不存在
     * @throws NoSuchProviderException 算法器不存在
     */
//    public static KeyPair genKeyPair() throws InvalidAlgorithmParameterException, NoSuchProviderException, NoSuchAlgorithmException {
//        ECGenParameterSpec sm2Spec = new ECGenParameterSpec("sm2p256v1");
//        // 获取一个椭圆曲线类型的密钥对生成器
//        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEYFACTORY_ALGORITHM, PROVIDER);
//        // 使用SM2参数初始化生成器
//        kpg.initialize(sm2Spec);
//    
//        // 使用SM2的算法区域初始化密钥生成器
//        kpg.initialize(sm2Spec, new SecureRandom());
//        // 获取密钥对
//        return kpg.generateKeyPair();
//    }
}
