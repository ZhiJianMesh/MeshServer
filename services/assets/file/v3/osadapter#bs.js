//only for test in explorer
const SERVICE_USER="user";
const SERVICE_UNIUSER="uniuser";
const SERVICE_CONFIG="config";
const SERVICE_ADDR="address";
const MAX_INT=2147483648;

const COMPANY_ID="cid";
const COMPANY_NAME="store_company_name";
const COMPANY_SERVERADDR="serverAddr";
const COMPANY_ACCESSCODE="accessCode";

const RetCode={
OK:0,
DEPRECATED:1,
INTERNAL_ERROR:100,
HTTP_ERROR:101,
INVALID_TOKEN:102, //token无效
EMPTY_BODY:103,
DB_ERROR:104,
INVALID_SESSION:105, //会话错误
SERVICE_NOT_FOUND:106, //服务不存在
TOO_BUSY:107,
SYSTEM_TIMEOUT:108,
NOT_SUPPORTED_FUNCTION:109, //API存在，但是所需的功能不支持
API_NOT_FOUND:110, //API不存在
NO_RIGHT:111, //无权限
NO_NODE:112, //没有合适的节点
INVALID_NODE:113, //不合适的节点
THIRD_PARTY_ERR:114, //第三方服务错误
PAST_DUE:115, //欠费
FORBIDDEN:118,//禁止访问
UNKNOWN_ERROR:150,
EXISTS:2000,
NOT_EXISTS:2001,
API_ERROR:3000,
WRONG_JSON_FORMAT:3001,
INVALID_VERSION:3002,
DATA_WRONG:3003,
WRONG_PARAMETER:4000,
SERVICE_ERROR:5000,
INVALID_STATE:5001,
CLIENT_ERROR:100000,
CLIENT_DBERROR:100001
};

const MAX_TASK_NUM=256;//最多MAX_TASK_NUM个并发任务
const __callback_funs=new Array(MAX_TASK_NUM);
var __call_id=0;
function __regsiterCallback(cb) {
    __call_id++;
    __callback_funs[__call_id % MAX_TASK_NUM]=cb;
    return __call_id;
}

function __unRegsiterCallback(jsCbId) {
    __callback_funs[jsCbId % MAX_TASK_NUM] = undefined;
}
//通用的回调，在java程序中触发，传的resp就是HandleResult
function __default_jscb(jsCbId, resp) {
    var f=__callback_funs[jsCbId % MAX_TASK_NUM];
    if(f && typeof(f)=='function') {
        f(resp);
        __unRegsiterCallback(jsCbId)
    } else {
        Console.error("__default_jscb `" + jsCbId + "` not exists");
    }
}

function saveCompanies() {
    var s=JSON.stringify(__companies);
    localStorage.setItem("companies", s);
    localStorage.setItem("curCompany", __curCompany);
}

function currentCompany() {
    return __companies[__curCompany];
}

var __request_cache={};
var __traceid=0;
function request(opts, service){
    if(!opts.headers) {
        opts.headers={};
    }

    if(opts.private!==undefined && !opts.private) { //公有，未传则默认私有
        return sendRequest(opts, service);
    }

    opts.headers['access_token']='';
    return getToken(service).then(resp => {
        if(resp.code != RetCode.OK) {
            return resp;
        }
        opts.headers.access_token=resp.data.access_token;
        return sendRequest(opts, service);
    });
}

function getToken(service) {
    var company=currentCompany();
    var st=company.tokens[service];
    if(st) {
        return new Promise((resolve, reject)=>{
            resolve({code:RetCode.OK, data:{access_token:st}});
        });
    }
    
    var userService=company.id==1?SERVICE_UNIUSER:SERVICE_USER;
    var at=company.tokens[userService];
    if(!at){//以用户token换服务token
        return new Promise((resolve, reject)=>{
            resolve({code:RetCode.NO_RIGHT, info:"no right"});
        });
    }
    var opts={method:'GET',
        url:'/api/serviceToken?service='+service,
        headers:{access_token:at}
    };
    return sendRequest(opts, userService).then(resp => {
        if(resp.code == RetCode.OK) {
            company.tokens[service]=resp.data.access_token;
            saveCompanies();
        }
        return resp;
    });
}

function sendRequest(opts, service) {
    /*var url=location.protocol+"//";
    if(opts.cloud) {
        url+="api.zhijian.net.cn:8523";
    } else {
        url+=location.host;
    }
    url+="/"+service;*/
    var url=opts.url;
    if(!url.startsWith("http://")&&!url.startsWith("https://")) {
        url=location.protocol+"//"+location.host+"/"+service;
        if(opts.url.startsWith("/api/")) {
            url += opts.url;
        } else {
            url += "/api" + opts.url;
        }
    }
    __traceid++;
    var hh = opts.headers;
    if(!hh) {
        hh={trace_id:'browser'+__traceid};
    } else {
        hh.trace_id='browser'+__traceid;
    }
    if(!hh.cid) {
        hh.cid=Companies.cid();
    }

    return new Promise((resolve, reject) => {
        var req = {method:opts.method, url:url, headers:hh};
        var method=opts.method.toUpperCase();
        if(method == "POST" || method == "PUT") {
            req.data = opts.data;
            opts.cache=false;//post请求不容许缓存
        } else if(opts.cache) {
            var cache=__request_cache[req.url]; //需要加超时时间控制
            if(cache!==undefined) {
                resolve(__request_cache[req.url]);
                return;
            }
        }

        axios(req).then(
        res => {
            if(res.status == 200) {
                console.info(service + "=>" + opts.url + ":" + JSON.stringify(res.data));
                if(opts.cache) {
                    __request_cache[req.url]=res.data;
                }
                resolve(res.data);
            } else {
                resolve({code:RetCode.HTTP_ERROR, info:"http error,status:" + res.status});
            }
        },
        err => {
            resolve({code:RetCode.HTTP_ERROR, info:"http error"})
        });
    });
}

//GET外部网站的内容
function getExternal(opts) {
    return new Promise((resolve, reject)=>{
    var req = {method:"GET", url:opts.url, headers:opts.headers};
    axios(req).then(
        res => {
            if(res.status == 200) {
                console.info("getExternal " + opts.url + ":" +res.data);
                resolve(res.data);
            } else {
                resolve("");
            }
        },
        err => {
            resolve(`{"introduce": {
            "descrs": [
               "满足小微企业客户关系管理所需，实现客户、联系人、订单、服务、回款管理，支持按工作流方式推动销售、服务、回款等工作。",
               "可自定义工作流步骤，每个步骤可以单个负责人签字，也可以多个参与人共同决策，方便企业实现工作流程规范化。",
               "实时数据统计，并呈现在主界面中，普通成员可以查看一月内的报表，企业主与财务可以查看年度与月度报表。",
               "精准的数据授权，只将数据授权给参与工作流的成员，其他成员通过数据分享查看数据，数据分享可设置期限。"
               ],
               "images": [
                   {
                       "src": "_imgs/1.png",
                       "info": "具备客户、联系人、订单管理功能"
                   },
                   {
                       "src": "_imgs/2.png",
                       "info": "同时支持回款、服务工作流操作"
                   },
                   {
                       "src": "_imgs/3.png",
                       "info": "实时报表呈现，及时掌握团队状态"
                   }
               ]
           }}`
        )}
    );
    })
}

function download(opts, service) {
    if(!opts.headers) {
        opts.headers={};
    }
    if(opts.private!==undefined && !opts.private) { //默认私有
        return innerDownload(opts, service);
    }
    opts.headers['access_token']='';
    return getToken(service).then(resp => {
        if(resp.code != RetCode.OK) {
            return resp;
        }
        opts.headers.access_token=resp.data.access_token;
        return innerDownload(opts, service);
    });
}

function innerDownload(opts, service) {
    var url=location.protocol+"//"+location.host+"/"+service + opts.url;
    __traceid++;

    opts.headers.trace_id='browser'+__traceid;
    opts.headers.cid=Companies.cid();

    return new Promise((resolve, reject)=>{
        axios({method:"GET",url:url,responseType:"blob", headers:opts.headers}).then(
        res => {
            if(res.status != 200) {
                Console.debug("fail to download " + url);
                resolve({code:RetCode.HTTP_ERROR, info:"status code " + res.status});
                return;
            }
            var contentType=res.headers['content-type'];
            if(!contentType) {
                contentType='application/octet-stream';
            }
            var fileName;
            var disposition=res.headers['content-disposition'];
            if(disposition) {
                var k='filename=';
                var pos=disposition.indexOf(k);
                if(pos>0) {
                    fileName=disposition.substring(pos+k.length,disposition.length)
                            .trim().replace('/', '_');
                    fileName=decodeURIComponent(fileName);
                }
            }
            if(!fileName){
                fineName=opts.url.replace('/', '_');
            }

            var size=res.data.size;
            var blob = new Blob([res.data], {type: contentType});
            var link = document.createElement('a');
            link.href = window.URL.createObjectURL(blob);
            link.download = fileName;
            link.click();
            window.URL.revokeObjectURL(link.href)//释放内存
            resolve({code:RetCode.OK, info:"Success", data:{size:size, saveAs:fileName}});
        },
        err => {
            Console.debug(err);
            resolve(500)
        });
    });
}

function appendParas(url, paras/*kv*/) {
    var sep=url.indexOf('?')>=0?'&':'?';
    var s=[url];
    for(var i in paras) {
        s.push(sep, i, '=', paras[i]);
        sep = '&';
    }
    return s.join('');
}
//只拷贝在segs中的字段
function copyObj(src,segs){
    if(segs && segs.length>0) {
        var dst={};
        for(var i of segs) {
            dst[i]=(i in src)?src[i]:'';
        }
        return dst;
    }
    return cloneObj(src);
}

//排除部分字段的拷贝
function copyObjExc(src,excludes){
    const excs = new Set(excludes);
    var obj = {};
    for(var k in src) {
        if(excs.has(k)) {
            continue;
        }
        obj[k] = src[k];
    }
    return obj;
}

function copyObjToExc(src,dst,excludes){
    const excs = new Set(excludes);
    for(var k in src) {
        if(excs.has(k)) {
            continue;
        }
        dst[k] = src[k];
    }
    return obj;
}

function copyObjTo(src,dst,segs){
    if(segs && segs.length>0) {
        for(var i of segs) {
            dst[i]=(i in src)?src[i]:'';
        }
    } else { //全部拷贝
        for(var i in src) {
            dst[i]=src[i];
        }
    }
}
function cloneObj(obj) {
    if(obj == null || obj == undefined) {return obj;}
    // Handle Array
    if (obj instanceof Array) {
        var copy = [];
        for (var o of obj) {
            copy.push(cloneObj(o));
        }
        return copy;
    }

    // Handle Object
    if (obj instanceof Object) {
        var copy = {};
        for(var k in obj) {
            copy[k] = cloneObj(obj[k]);
        }
        return copy;
    }

    return obj;
}

function sleep(time) {
  return new Promise((resolve) => setTimeout(resolve, time));
}

function readText(txt) {
    return new Promise(function(resolve, reject){
        console.info("read text " + txt);
        setTimeout(function() {resolve('done');}, 500);
    });
}

const JStr={
    creditCodeOrigin:'0123456789ABCDEFGHJKLMNPQRTUWXY', //可用字符，不含I、O、S、V、Z
    creditWeightFactors:[1,3,9,27,19,26,16,17,20,29,25,13,8,24,10,30,28], //对应顺序的加权因子 
    chkCreditCode(code) {
        if(!code || code.length !== 18) {//空值返回false
            return false;
        }
        
        let ci; // 统一社会信用代码相应顺序的值
        let wi; // 统一社会信用代码相应顺序的加权因子
        let total = 0; // 计算结果
    
        // 数值与加权因子相乘之和
        for(let i = 0; i < code.length - 1; i++) {
            ci = this.creditCodeOrigin.indexOf(code[i]);
            wi = this.creditWeightFactors[i];
            total += ci*wi; 
        }
    
        // 最后一位校验
        let logicCheckCode = 31 - total % 31;
        if (logicCheckCode === 31) logicCheckCode = 0;
        logicCheckCode = this.creditCodeOrigin[logicCheckCode];
        return logicCheckCode===code.slice(17);
    },
    idNoReg:/^(\d{6})(\d{4})(\d{2})(\d{2})(\d{3})([0-9]|X|x)$/,
    idNoWeight:[7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2],
    idNoBasecode:['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'],
    chkIdNo(no) {
        if(!idNoReg.test(no)){
            return false;
        }
        //检验18位身份证的校验码是否正确。
        //校验位按照ISO 7064:1983.MOD 11-2的规定生成，X可以认为是数字10。
        var verifyCode;
        var v = 0;
        for(var i = 0; i < 17; i++) {
            v += no.charCodeAt(i) * idNoWeight[i];
        }
        verifyCode = idNoBasecode[v % idNoBasecode.length];
        return (verifyCode == no.substr(17, 1).toUpperCase());
    },
    base64CharCode(c) {
        if (c >= 'A' && c <= 'Z') {
            return c.charCodeAt(0) - 'A'.charCodeAt(0) + 10;
        } else if (c >= 'a' && c <= 'z') {
            return c.charCodeAt(0) - 'a'.charCodeAt(0) + 36;
        } else if (c >= '0' && c <= '9') {
            return c.charCodeAt(0) - '0'.charCodeAt(0);
        } else if (c == '-') {
            return 62;
        } else {
            return 63;
        }
    },
    base64Char(v) {
        if (v >= 0 && v <= 9) {
            return String.fromCharCode('0'.charCodeAt(0) + v);
        } else if (v >= 10 && v <= 35) {
            return String.fromCharCode('a'.charCodeAt(0) + v);
        } else if (v >= 36 && v <= 52) {
            return String.fromCharCode('A'.charCodeAt(0) + v);
        } else  if (c == 62) {
            return '-';
        } else {
            return '_';
        }
    },
    isLanIP(ip) {
        if(ip.indexOf(':') > 0) {
            if(ip.startsWith("fe8")||ip.startsWith("fec")) {
                return true;
            }
            return false;
        }
        if(ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return true;
        }
        if(ip.startsWith("172.")) {
            var pos = ip.indexOf('.', 4);
            var second = Integer.parseInt(ip.substring(4, pos));
            return second >= 16 && second <= 31;
        }
        return false;
    },
    isIPv4(ip) {
        return /^([0-9]{1,3}\.){3}[0-9]{1,3}$/.test(ip);
    },
    isIPv6(ip) {
        if(ip.indexOf('::')<0) {//压缩格式，最多包含一个::
            return /^([A-Fa-f0-9]{1,4}:){7}[A-Fa-f0-9]{1,4}$/.test(ip);
        }
        return /^([A-Fa-f0-9]{1,4}:){1,6}:([A-Fa-f0-9]{1,4})?$/.test(ip);
    }
}

const Secure = {
    sha256(s) { //https://blog.csdn.net/yuanyuan95/article/details/127811272
        const chrsz = 8
        const __base64Chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_';
    
        function safe_add(x, y) {
            const lsw = (x & 0xFFFF) + (y & 0xFFFF)
            const msw = (x >> 16) + (y >> 16) + (lsw >> 16)
            return (msw << 16) | (lsw & 0xFFFF)
        }
    
        function S(X, n) {
            return (X >>> n) | (X << (32 - n))
        }
    
        function R(X, n) {
            return (X >>> n)
        }
    
        function Ch(x, y, z) {
            return ((x & y) ^ ((~x) & z))
        }
    
        function Maj(x, y, z) {
            return ((x & y) ^ (x & z) ^ (y & z))
        }
    
        function Sigma0256(x) {
            return (S(x, 2) ^ S(x, 13) ^ S(x, 22))
        }
    
        function Sigma1256(x) {
            return (S(x, 6) ^ S(x, 11) ^ S(x, 25))
        }
    
        function Gamma0256(x) {
            return (S(x, 7) ^ S(x, 18) ^ R(x, 3))
        }
    
        function Gamma1256(x) {
            return (S(x, 17) ^ S(x, 19) ^ R(x, 10))
        }
    
        function coreSha256(m, l) {
            const K = [
                0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
                0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
                0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
                0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
                0xE49B69C1, 0xEFBE4786, 0xFC19DC6, 0x240CA1CC,
                0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
                0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
                0xC6E00BF3, 0xD5A79147, 0x6CA6351, 0x14292967,
                0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
                0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
                0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
                0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
                0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
                0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
                0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
                0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2]
            const HASH = [
                0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
                0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19]
            const W = new Array(64)
            var a, b, c, d, e, f, g, h, i, j
            var T1, T2
            m[l >> 5] |= 0x80 << (24 - l % 32)
            m[((l + 64 >> 9) << 4) + 15] = l
            for (i = 0; i < m.length; i += 16) {
                a = HASH[0]
                b = HASH[1]
                c = HASH[2]
                d = HASH[3]
                e = HASH[4]
                f = HASH[5]
                g = HASH[6]
                h = HASH[7]
                for (j = 0; j < 64; j++) {
                    if (j < 16) {
                        W[j] = m[j + i]
                    } else {
                        W[j] = safe_add(safe_add(safe_add(Gamma1256(W[j - 2]), W[j - 7]), Gamma0256(W[j - 15])), W[j - 16])
                    }
                    T1 = safe_add(safe_add(safe_add(safe_add(h, Sigma1256(e)), Ch(e, f, g)), K[j]), W[j])
                    T2 = safe_add(Sigma0256(a), Maj(a, b, c))
                    h = g
                    g = f
                    f = e
                    e = safe_add(d, T1)
                    d = c
                    c = b
                    b = a
                    a = safe_add(T1, T2)
                }
                HASH[0] = safe_add(a, HASH[0])
                HASH[1] = safe_add(b, HASH[1])
                HASH[2] = safe_add(c, HASH[2])
                HASH[3] = safe_add(d, HASH[3])
                HASH[4] = safe_add(e, HASH[4])
                HASH[5] = safe_add(f, HASH[5])
                HASH[6] = safe_add(g, HASH[6])
                HASH[7] = safe_add(h, HASH[7])
            }
            return HASH
        }
    
        function str2bin(str) {
            const bin = []
            const mask = (1 << chrsz) - 1
            for (var i = 0; i < str.length * chrsz; i += chrsz) {
                bin[i >> 5] |= (str.charCodeAt(i / chrsz) & mask) << (24 - i % 32)
            }
            return bin
        }
    
        function utf8Encode(s) {
            s = s.replace(/\r\n/g, '\n')
            var utfText = ''
            for (var n = 0; n < s.length; n++) {
                const c = s.charCodeAt(n)
                if (c < 128) {
                    utfText += String.fromCharCode(c)
                } else if ((c > 127) && (c < 2048)) {
                    utfText += String.fromCharCode((c >> 6) | 192)
                    utfText += String.fromCharCode((c & 63) | 128)
                } else {
                    utfText += String.fromCharCode((c >> 12) | 224)
                    utfText += String.fromCharCode(((c >> 6) & 63) | 128)
                    utfText += String.fromCharCode((c & 63) | 128)
                }
            }
            return utfText
        }
        
        function bin2base64(bytes) {
            var str = '';
            var left = 0;
            var len = bytes.length;
            var v = 0;
            var j = 24;
        
            for (var i = 0; i < len; i++) {
                for(j = 24; j>=0; j-=8) {
                    v |= (((bytes[i] >> j) & 0xff) << left);
                    left += 8;
                    while (left >= 6) {
                        str += __base64Chars.charAt(v & 0x3f);
                        v >>>= 6;
                        left -= 6;
                    }
                }
            }
            if (left > 0) {
                str += __base64Chars.charAt(v & 0x3f);
            }
            return str;
        }
        
        var s = utf8Encode(s);
        var b = coreSha256(str2bin(s), s.length * chrsz);
        return bin2base64(b);
    }
}

const Console = {
    debug(s) {console.debug(s)},
    info(s) {console.info(s)},
    warn(s) {console.warn(s)},
    error(s) {console.error(s)}
}

const Server = {
    state:0,
    getState(jsCbId) {
        __default_jscb(jsCbId,{code:0,data:{state:this.state}});
    },
    fileCalls() {return 29999},
    apiCalls() {return 1000},
    alter(jsCbId) {
        if(this.state!=this.SRV_RUNNING()) {
            this.state=this.SRV_ALTERING();
            sleep(2000).then(()=> {
                this.state=this.SRV_RUNNING();
                __default_jscb(jsCbId,{code:0,data:{state:this.state}});
            })
        } else {
            this.state=this.SRV_ALTERING();
            sleep(1000).then(()=> {
                this.state=this.SRV_CLOSED();
                __default_jscb(jsCbId,{code:0,data:{state:this.state}});
            })
        }
    },
    address() {return "192.168.1.1:8523"},
    gw() {return "[240e:999:c40:9c82:5201:9999:fec2:9999]:8523"},
    runTime() {return 100000},
    startupAt() {return 1657157855534},
    getServiceVer(s){return 1000},
    serviceIcon(s) {return "/" + s + "/favicon.png"},
    installService(s,jsCbId){
        for(var i=0;i<100;i++) {
            sleep(30 * i).then(()=>{
                installProgress(1,"test"+i);
            })
        }
        sleep(2000).then(()=>{
            __default_jscb(jsCbId,{code:RetCode.OK});
        })
    },
    unInstallService(s,jsCbId){
        sleep(2000).then(()=>{
            __default_jscb(jsCbId,{code:RetCode.OK});
        })
    },
    updateService(s,jsCbId){
        sleep(2000).then(()=>{
            __default_jscb(jsCbId,{code:RetCode.OK});
        })
    },
    resetTokenPwd(){return '1234567890123456'},
    resetAccessCode(jsCbId){
        __default_jscb(jsCbId,{code:RetCode.OK, data:{code:"723456"}});
    },
    saveAccessCode(code, jsCbId){
        __default_jscb(jsCbId,{code:RetCode.OK, data:{code:code}});
    },
    getServices(jsCbId){ //只是用于测试
        var data={code:RetCode.OK, data:{services:[
            {name:"crm",displayName:"客户关系管理",author:"Lgy", level:5, favicon:"/crm/favicon.png", version:"0.1.0",updatable:false},
            {name:"member",displayName:"会员",author:"Lgy",level:6, updatablefavicon:"/member/favicon.png", version:"0.1.0",updatable:true, srvVer:"0.2.0"},
            {name:"user",displayName:"用户服务",author:"Lgy",level:3, favicon:"/user/favicon.png", version:"0.1.0",updatable:true, srvVer:"0.2.0"},
            {name:"enstu",displayName:"英语教学",author:"Lgy",level:100, favicon:"/enstu/favicon.png", version:"0.1.0",updatable:true, srvVer:"0.2.0"},
            {name:"bios",displayName:"注册发现服务",author:"Lgy",level:0, favicon:"/bios/favicon.png", version:"0.1.0",updatable:false},
            {name:"oauth2",displayName:"鉴权服务",author:"Lgy",level:1, favicon:"/assets/favicon.png", version:"0.1.0",updatable:false}
        ]}};
        __default_jscb(jsCbId,data);
    },
    serviceStarted() {return true},
    setLogLevel(level) {},
    setOutsideAddr(addr, jsCbId) {
        __default_jscb(jsCbId,{code:RetCode.OK,info:'Success'});
    },
    setBackupAt(at, jsCbId) {
        __default_jscb(jsCbId,{code:RetCode.OK,info:'Success'});
    },
    saveFile(service, file, content) {},
    command(reqDta) {
        var opts={method:"POST", url:"/command", data:reqDta};
        return request(opts, 'company');
    },
    setName(name,jsCbId) {
        this.command({cmd:"setInfo", name:name}).then(resp => {
            __default_jscb(jsCbId,resp);
        });
    },
    setLocation(province,city,county,jsCbId) {
        this.command({cmd:"setInfo",
            province:province,
            city:city,
            county:county
        }).then(resp=> {
            __default_jscb(jsCbId,resp);
        });
    },
    query(items, jsCbId) {
        var ii=items.split(",");
        var item;
        var data={};
        for(var i of ii) {
            item = i.trim().toLowerCase();
            if(item == "loglevel") {
                data[i] = "DEBUG";
            } else if(item == "logpath") {
                data[i] = "e:\\work\\code\\cloudserver";
            } else if(item == "accesscode") {
                data[i] = "12345678";
            } else if(item == "backupat") {
                data["recent"] = (new Date()).getTime(); //最近备份时间
                data[i] = 65;
            } else if(item == "companyid") {
                data[i] = 50;
            } else if(item == "creditcode") {
                data[i] = "1111111111";
            } else if(item == "companyname") {
                data[i] = "南京汇想";
            } else if(item == "country") {
                data[i] = "86";
            } else if(item == "county") {
                data[i] = "江宁区";
            } else if(item == "province") {
                data[i] = "江苏省";
            } else if(item == "city") {
                data[i] = "南京市";
            } else if(item == "info") {
                data[i] = "";
            } else if(item == "outsideaddr") {
                data[i] = "240e:3af:c40:9110:de5f:9919:bf8f:79f9";
            } else if(item == "insideaddr") {
                data[i] = "192.168.0.102";
            } else if(item == "externaddrs") {
                data[i]=["240e:3af:c40:9110:de5f:9919:bf8f:79f9",
                "240e:3af:c40:9110:b5c5:2d97:f998:2df4",
                "240e:3af:c40:9110::1000"]
            } else {
                Console.warn("Invalid query item " + i);
            }
        }
        __default_jscb(jsCbId,{code:RetCode.OK,info:"Success", data:data});
    },
    intToVer(v) {return    Math.floor(v/1000000)+'.'+(Math.floor(v/1000)%1000)+'.'+(v%1000);},
    SRV_RUNNING() { return 3;},
    SRV_CLOSED() {return 4;},
    SRV_ALTERING() {return 2;}
}

const Company={//used in server ui
    id(){return 50}, //storageGet(COMPANY_ID,'0')
    name(){return "test"},
    register(creditCode,pwd,cfmPwd,name,country,province,city,county,info,verifyCode,session,jsCbId){
        var data={creditCode:creditCode,
            pwd:Secure.sha256(pwd),
            cfmPwd:Secure.sha256(cfmPwd),
            verifyCode:verifyCode,session:session,
            authKey:'1&IBs8SxVSe4Hu+GX4Q7wUIHOmGQcERRBhCVh80D/2m3qCXPXdpH5KwRBjAmtAxGKoI+EG3DNsvsfipxXdfbux7ps=',
            name:name,
            partition:250000,info:info,
            country:country,province:province,city:city,county:county
        };
        var opts={url:"/company/register", method:"POST", data:data, private:false}
        request(opts, "company").then(resp=>{
            __default_jscb(jsCbId, resp)
        })
    },
    login(id,pwd,jsCbId){
        var data={id:id,pwd:Secure.sha256(pwd),authKey:'1&IBs8SxVSe4Hu+GX4Q7wUIHOmGQcERRBhCVh80D/2m3qCXPXdpH5KwRBjAmtAxGKoI+EG3DNsvsfipxXdfbux7ps='};
        var opts={url:"/company/login", method:"POST", data:data, private:false}
        request(opts, "company").then(resp=>{
            __default_jscb(jsCbId, resp)
        });
    },
    unRegister(creditCode,pwd,jsCbId){
        __default_jscb(jsCbId, {code:RetCode.OK,info:"success"})
    },
    getLogo(){
        var logoUrl=Http.cloudFileUrl("/logo?cid="+this.cid, "company");
        return getExternal({url:logoUrl});
    },
    saveLogo(){},
    changePwd(oldPwd, newPwd, cfmPwd, jsCbId) {
        __default_jscb(jsCbId, {code:RetCode.OK,info:"success"})
    }
}

const Http={
    cloudFileUrl(uri, service) {
        var url=location.protocol+"//"+location.host+"/"+service;
        if(!uri.startsWith("/")) {
            url += '/';
        }
        return url + uri;
    },
    isLanIP(ip) { //用在serverui中，仅用于测试
        return JStr.isLanIP(ip);
    },
    isIPv4(ip) {
        return JStr.isIPv4(ip);
    },
    isIPv6(ip) {
        return JStr.isIPv6(ip);
    },
    localIPs() {
        return "192.168.1.25,240e:3af:c40:c280:545b:3fea:e8e0:7794,fe80::3943:28bb:6a80:d0ac%3";
    }
};

var __companies=[];
var __curCompany=0;
const Companies={
    cid(){return currentCompany().id},
    insideAddr() {return currentCompany().insideAddr;},
    outsideAddr() {return currentCompany().outsideAddr;},
    accessCode(){return currentCompany().accessCode},
    userService(){return currentCompany().id==1?SERVICE_UNIUSER:SERVICE_USER},
    rootCompanyId(){return 1},
    refreshEntrance(){},
    authorized(){return (this.userService() in currentCompany().tokens)},
    login(acc,pwd,tp,jsCbId) {
        var company=currentCompany();
        var userService=this.userService();
        var dta={account:acc,password:pwd,grant_type:'password',accType:tp/*uniuser中才会需要*/};
        sendRequest({method:'POST',url:'/api/login',private:false,data:dta}, userService).then(resp=> {
            if(resp.code==RetCode.OK) {
                company.tokens[userService]=resp.data.access_token;
                company.authorized=true;
                company.uid=resp.data.id;
                saveCompanies();
            }
            __default_jscb(jsCbId,resp)
        })
    },
    logout(jsCbId) {
        var company=currentCompany();
        var userService=this.userService();
        var at=company.tokens[userService];
        var opts={method:'GET',url:'/api/logout',headers:{access_token:at}};
        sendRequest(opts, userService).then(resp => {
            company.tokens={};
            company.authorized=false;
            saveCompanies();
            __default_jscb(jsCbId,resp)
        })
    },
    add(cid,accessCode,insideAddr,jsCbId) {
        var token=Secure.sha256(accessCode);
        var opts={method:'GET',url:'/api/company/entrance?id='+cid,
            headers:{access_token:token,cid:cid},private:false};
        sendRequest(opts, "httpdns").then(resp => {
            if(resp.code!=RetCode.OK) {
                __default_jscb(jsCbId, resp);
                return;
            }
            var c=null;
            for(var company of __companies) {
                if(company.id==cid) {
                    c = company;
                    break;
                }
            }
            if(c) {
                c.accessCode=accessCode;
                c.insideAddr=resp.data.insideAddr;
                c.outsideAddr=resp.data.outsideAddr;
                c.userService=SERVICE_USER;
                c.name=resp.data.name;
            } else {
                __companies.push({id:cid,
                    accessCode:accessCode,
                    insideAddr:resp.data.insideAddr,
                    outsideAddr:resp.data.outsideAddr,
                    name:resp.data.name,
                    userService:SERVICE_USER,
                    tokens:{}
                });
                __curCompany=__companies.length-1;
            }
            saveCompanies();
            __default_jscb(jsCbId, resp);
        })
    },
    remove(cid) {
        if(cid==0) {//个人不可删除
            return false;
        }
        var idx = -1;
        for(var c of __companies) {
            idx++;
            if(c.id==cid) {
                break;
            }
        }
        if(idx<0) {
            return false;
        }
        __companies.splice(idx, 1);
        if(idx==__curCompany) {
            __curCompany=0;
        }
        saveCompanies();
        return true;
    },
    setCur(cid, jsCbId){
        if(__companies[__curCompany].id==cid) return;

        var idx = -1;
        for(var c of __companies) {
            idx++;
            if(c.id==cid) {
                __curCompany=idx;
                localStorage.setItem("curCompany", __curCompany);
                break;
            }
        }
        __default_jscb(jsCbId, {code:0});        
    },
    curCompany(){
        var c = __companies[__curCompany];
        if(c) {
            c.userService=c.id==1?SERVICE_UNIUSER:SERVICE_USER;
            return JSON.stringify(c);
        }
        return "{}";
    },
    curCompanyId() {
        return __companies[__curCompany].id;
    },
    list(jsCbId) {
        __default_jscb(jsCbId,{code:RetCode.OK,info:"success",data:{list:__companies}});
    },
    getLogo(cid, jsCbId) {
        var logoUrl=Http.cloudFileUrl("/logo?cid="+cid, "company");
        getExternal({url:logoUrl}).then(png => {
            __default_jscb(jsCbId,png);
        });
    },
    saveLogo(cid, logo) {}
}

const App={
    openApp(app) {location.href='/'+app+'/index.html'},
    currentApp() {
        var s=location.href;
        var pos=s.indexOf("//")
        var ss=s.substring(pos+2).split("/"); //跳过https://或http://xx
        return ss[1];//http://a.b.c.d/service/index.html
    },
    install(app, jsCbId) {
         Console.info("install app " + app);
        __default_jscb(jsCbId,{code:RetCode.OK,info:"success"});
    },
    unInstall(service, jsCbId) {
         console.info("uninstall app " + app);
        __default_jscb(jsCbId,{code:RetCode.OK,info:"success"});
    },
    serviceIcon(s) {return "/" + s + "/favicon.png"},
    list() { return JSON.stringify([
        {name:"crm",displayName:"客户关系管理",version:"1000"},
        {name:"member",displayName:"会员",version:"1000"},
        {name:"user",displayName:"用户服务",version:"1000"},
        {name:"enstu",displayName:"英语教学",version:"1000"},
        {name:"bios",displayName:"注册发现服务",version:"1000"},
        {name:"oauth2",displayName:"鉴权服务",version:"1000"}
    ])},
    logPath(){return "d:\\work\\code\\release"},
    isInstalled(){return true},
    isBuiltin(app) {return app=="market"||app=="settings"||app=="about"||app=="assets"},
    verToInt(v){
        var ss=v.split('.');
        return parseInt(ss[0])*1000000+parseInt(ss[1])*1000+parseInt(ss[2]);
    },
    intToVer(v) {return Math.floor(v/1000000)+'.'+(Math.floor(v/1000)%1000)+'.'+(v%1000);},
    build(){return {ver:"0.1.0",os:"android",brand:"zhijian",language:"zh_CN",agent:"mc_android_0.1.0"}}
}

const Platform={
    scanCode(jsCbId) {
        var info={act:"checkin",id:40,code:"ABCDFFGG",addr:"localhost:8523"};
        var data={type:'QrCode',value:JSON.stringify(info)};
        __default_jscb(jsCbId,{code:RetCode.OK,info:"success", data:data})
    },
    isSupported(name) {
        if(name=="barcode") {
            return true;
        } else if(name=="orientation") {
            return false;
        }
        return false;
    },
    language() {
        return navigator.language.substring(0,2).toLowerCase();
    },
    width() {return document.documentElement.clientWidth},
    height() {return document.documentElement.clientHeight},
    portrait() {},
    landscape() {},
    version(){return "browser_test_1.0.1"},
    undefineOrientation() {}
}

const Logs = {
    listLogs(jsCbId) {__default_jscb(jsCbId,{code:RetCode.OK,info:"success", data:{list:["a","b"]}}) },
    download(f,jsCbId) {__default_jscb(jsCbId,{code:RetCode.OK,info:"success", data:{saveAs:"test",size:100}})}
}

function findInArray(arr, s) {
    var no=0;
    for(var a of arr) {
        if(a==s) {
            return no;
        }
        no++;
    }
    return -1;
}

const Database = {
    dbs:{},
    open(name) {
        var db = this.dbs[name];
        if(!db) {
            db = openDatabase(name, '1.0', '', 10 * 1024 * 1024);
            this.dbs[name]=db;
        }
    },
    close(name) {},
    remove(name) {},

    execute(name, sql) {
        var db=this.dbs[name];
        if(!db) {return {code:RetCode.CLIENT_DBERROR, info:"no such db"}};
        return new Promise(resolve=>{
            db.transaction(tx=>{
                tx.executeSql(sql, [],
                    (tx, res) => {
                        resolve({code:RetCode.OK, data:res});
                    },
                    (tx, err) => {
                        resolve({code:RetCode.CLIENT_DBERROR, info:err.message});
                    });
                }
            )
        })
    },

    executes(name, sqls) {
        var db=this.dbs[name];
        if(!db) {return {code:RetCode.CLIENT_DBERROR, info:"no such db"}};
        var ss = sqls.split(";");
        return new Promise(resolve=>{
            db.transaction(tx=>{
                this.txExecSql(tx, ss, 0).then(res => {
                    return resolve(res);
                });
            })
        });
    },

    initialize(name, sqls) {
        return this.executes(name, sqls);
    },

    /*private*/txExecSql(tx, sqls, i) {
        return new Promise(resolve=>{
            tx.executeSql(sqls[i], [],
                (tx, res) => {
                    if(i < sqls.length-1) {
                        this.txExecSql(tx, sqls, i+1).then(r => {
                            resolve(r);
                        });
                    } else {//执行完成了
                        resolve({code:RetCode.OK, data:res});
                    }
                },
                (tx, err) => {
                    resolve({code:6000, info:err.message});
                }
            );
        })
    },

    queryArrays(name, sql) {return new Promise(resolve=>{
        return this.execute(name, sql).then(res => {
            var arr = [];
            for(var item of res.data.rows.item) {
                var line = []
                for(var i in item) {
                    line.push(item[i]);
                }
                arr.push(line);
            }
            resolve({code:RetCode.OK, data:{rows:arr}});
        });
    })},

    queryMaps(name, sql) {return new Promise(resolve=>{
        return this.execute(name, sql).then(res => {
            var arr = [];
            for(var item of res.data.rows.item) {
                arr.push(item);
            }
            resolve({code:RetCode.OK, data:{rows:arr}});
        });
    })},

    queryMap(name, sql) {return new Promise(resolve=>{
        return this.execute(name, sql).then(res => {
            resolve({code:RetCode.OK, data:{rows:res.data.item}});
        });
    })}
}

function storageSet(k, v) {
    var key=App.currentApp()+'_'+k;
    localStorage.setItem(key,v);
}
function storageGet(k, def) {
    var key=App.currentApp()+'_'+k;
    var v=localStorage.getItem(key);
    if(!v) return def;
    return v;
}
function storageRmv(k) {
    var key=App.currentApp()+'_'+k;
    localStorage.removeItem(key);
}

(() => {
    var s=localStorage.getItem("companies");
    if(s) {
        __companies=JSON.parse(s);
    } else {
        __companies=[{id:1,name:"通用服务中心",accessCode:"ABCDEFGHIJ",tokens:{}}]
    }
    __curCompany=localStorage.getItem("curCompany");
    if(!__curCompany || __curCompany >= __companies.length) {
        __curCompany = 0;
    }
})();

function formatErr(code,info,errInfos){
    var err=__err_infos[''+code];
    if(!err) {
        err = errInfos[''+code];
    }
    if(!err){
        if(code>=4000&&code<5000) {
            err=__err_infos['4000'];
        } else if(code>=5000){
            err=__err_infos['5000']
        } else {
            err=__err_infos['unknown'];
        }
    }
    var msg = "";
    if(info instanceof Array) {
        for(var i of info) {
            msg += i + '<br>';
        }
    } else {
        msg = info;
    }
    return err + "["+code+"]:" + msg;
}

function addErrInfo(code,info) {
    __err_infos[''+code]=info;
}

function loadJs(url) {
    return new Promise((resolve) => {
        var script = document.createElement('script');
        script.type = 'text/javascript';
        script.src = url;
        script.onload = function() {
            resolve(true);
        };
        script.onerror = function() {
            console.error('fail to load js:' + url);
            resolve(false);
        };
        document.head.appendChild(script);
    });
}
loadJs('/assets/v3/tags/'+Platform.language()+'.js');
loadJs('/assets/axios_0.21.1.js');