const SERVICE_USER="user";
const SERVICE_UNIUSER="uniuser";
const SERVICE_CONFIG="config";
const SERVICE_ADDR="address";
const MAX_INT=2147483648; //4字节整数最大值

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
function request(opts,service) {
    var jsonOpts = JSON.stringify(opts);
    return new Promise(resolve=>{
        Http.request(jsonOpts, service,  __regsiterCallback(resp => {
            resolve(resp);
        }));
    });
}

function getExternal(opts) {
    var jsonOpts = JSON.stringify(opts);
    return new Promise(resolve=>{
        Http.getExternal(jsonOpts, __regsiterCallback(resp => {
            resolve(resp);
        }));
    });
}

function download(opts, service) {
    var jsonOpts = JSON.stringify(opts);
    return new Promise(resolve=>{
        Http.download(jsonOpts, service, __regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{size:yy,path:'path of local saved file'}}
        }));
    });
}

function upload(opts, service) {
    var jsonOpts = JSON.stringify(opts);
    return new Promise(resolve=>{
        Http.upload(jsonOpts, service, __regsiterCallback(resp => {
            resolve(resp);
        }));
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

function readText(txt){
    return new Promise(resolve=>{
        TTS.read(txt, _regsiterCallback(resp => {
            resolve(resp);
        }));
    });
}

//在TextToVoice的回调中触发此函数
function __tts_jscb(jsCbId, resp) {
    var f=__callback_funs[jsCbId % MAX_TASK_NUM];
    if(f && typeof(f)=='function') {
        status = resp.data.status;
        f(status);
        if(status=='done' || status=='error') {
            __unRegsiterCallback(jsCbId)
        }
    } else {
        Console.error("__tts_jscb " + jsCbId + " not exists");
    }
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

function __regsiterCallback(cb) {
    __call_id++;
    __callback_funs[__call_id % MAX_TASK_NUM]=cb;
    return __call_id;
}

function __unRegsiterCallback(jsCbId) {
    __callback_funs[jsCbId % MAX_TASK_NUM] = undefined;
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

function sleep(time) {
  return new Promise((resolve) => setTimeout(resolve, time));
}

function formatErr(code,info,errInfos){
    var err=__err_infos[''+code];//__err_infos在assets/tags中定义
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

const Database = {
    open(db) {
        return NativeDB.open(db);
    },
    initialize(db, sqls) {return new Promise(resolve=>{
        NativeDB.initialize(db,sqls,__regsiterCallback(resp => {
            resolve(resp);
        }));
    })},
    execute(db, sql) {return new Promise(resolve=>{
        NativeDB.execute(db,sql,__regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{lineNum:"affected line num"}}
        }));
    })},
    executes(db, sqls) {return new Promise(resolve=>{
        NativeDB.executes(db,sqls,__regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{lineNum:"affected line num"}}
        }));
    })},
    queryArrays(db, sql) {return new Promise(resolve=>{
        NativeDB.queryArrays(db,sql,__regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{rows:[[...]...]}}
        }));
    })},
    queryMaps(db, sql) {return new Promise(resolve=>{
        NativeDB.queryMaps(db,sql,__regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{rows:[{...}...]}}
        }));
    })},
    queryMap(db, sql) {return new Promise(resolve=>{
        NativeDB.queryMap(db,sql,__regsiterCallback(resp => {
            resolve(resp); //{code:xx,info:'',data:{...}}
        }));
    })},
    close(db) {
        NativeDB.close(db);
    },
    remove(db) {
        NativeDB.remove(db);
    }
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
            console.error('Fail to load ' + url);
            resolve(false);
        };
        document.head.appendChild(script);
    });
}
loadJs('/assets/v3/tags/'+Platform.language()+'.js');