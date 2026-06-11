package cn.net.zhijian.mesh.frm.intf;
/**
 * 服务间互调、服务内不同节点间互调、用户调用服务时，需要先向oAuth2申请token
 * Token格式：
 *   Ver(1)+签名类型(1)+Len(3)+JSON(uid/caller、expireAt、callee、
 *   [features(只有服务间调用会返回features)])+SH256/ECC签名
 *   签名类型：0-密码本（申请与验证需要通过oAuth或User完成）；
 *          1-运维私钥签名（用运维私钥生成签名，用公钥验证签名）；
 *          2-服务私钥签名（服务间调用，以及服务调用bios、oAuth时，使用应用私钥签名，被调方用应用公用验签）；
 * @author flyinmind of csdn.net
 *
 */
public interface IOAuth extends IConst {
    String SEG_ACCESS_TOKEN = HEAD_ACCESS_TOKEN;
    String SEG_REFRESH_TOKEN = "refresh_token";
    String SEG_EXPIRES_AT = "expires_at";
    String SEG_TOKEN_TYPE = "token_type";
    
    byte POWER_EXRERNAL_ACCESS = 0; //外网访问权限

    int TOKENTYPE_SERVICE = 0; //服务间调用
    int TOKENTYPE_DB = 1; //调用webdb，一种特殊的服务
    int TOKENTYPE_USER = 2; //用户认证
    int TOKENTYPE_MAX = 2;

    /**
     * SHARE是一种特殊的用户认证，用于分享数据给其他用户。
     * 比如用户A将权限授予用户B，用户A向user服务申请一个sharetoken，
     * 分享给用户B，用户B用这个token访问用户A的数据，如同用户A自己发出的访问
     */
    String[] TOKENTYPE_NAMES = new String[] {"service", "db", "user"};

    int SIGNTYPE_NONE = -1; //public
    int SIGNTYPE_CODEBOOK = 0; //OAuth密码本签名
    //运维平台私钥签名OMKEY，本质是一个特殊的APPKEY
    int SIGNTYPE_OMKEY = 1;
    //服务私钥签名，通常用于预置publicKey的情况，比如oAuth与bios之间，服务之间
    int SIGNTYPE_APPKEY = 2;
    int SIGNTYPE_COMPANYKEY = 3; //公司私有云私钥签名，必须与TOKENTYPE_USER配合使用
    int SIGNTYPE_PWD = 4; //使用密码签名
    int SIGNTYPE_MAX = 4;
    String[] SIGNTYPE_NAMES = new String[] {"codebook", "omkey", "appkey", "companykey", "pwd"};

    String USER_TOKEN_CHECKER = "USER"; //请求来自端侧，通过密码本签名
    String UNIUSER_TOKEN_CHECKER = "UNIUSER"; //请求来自端侧，通过密码本签名，个人服务才使用此token
    String BASEUSER_TOKEN_CHECKER = "BASEUSER"; //请求来自端侧，通过密码本签名，不检查外网权限
    String COMPANY_TOKEN_CHECKER = "COMPANY"; //请求来自私有云服务端，通过私钥签名，公有云用公司公钥验签
    String OAUTH_TOKEN_CHECKER = "OAUTH"; //服务间调用，请求oAuth2服务获取token，通过密码本签名
    String APP_TOKEN_CHECKER = "APP"; //服务间调用，用应用私钥签名生成token
    String OM_TOKEN_CHECKER = "OM"; //服务间调用，用OM私钥（一种特殊的应用私钥）生成token
    String INIT_TOKEN_CHECKER = "INIT"; //服务初始化时使用，可以用OM、APP或Backend
    String MNT_TOKEN_CHECKER = "MNT"; //Maintenance维护接口中使用，可以用OM或Backend
    String BACKEND_TOKEN_CHECKER = "BACKEND"; //既可以使用company，也可以使用临时接入码验证
    
    String ADMIN_UID = "1"; //超级管理员的ID固定为1，在user数据库初始化时即已确定
    String ADMIN_ACCOUNT = "admin";
}