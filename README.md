# MeshServer
An extremely tiny server written by Java, very easy to develop apis. Built in Reliability,Safety,High-Performance and Extendibility. Use it, you will not be disappointed!
More about it, refer to the project [HomePage](http://www.zhijian.net.cn/).

## Advantages
1. It can support OS linux/windows/termux/android;
2. It is very easy to develop apis to support your business, especially for apis which based on database;
3. It is very very small. You can even run it on an old Android phone or a Raspberry Pi;
4. It is very big. It can run as a cluster in public or private cloud, and backup among different cities;
5. So many Reliability,Safety,High-Performance and Extendibility realizations are builtin it;
6. It has many example applications in [Enterpris](https://github.com/ZhiJianMesh/endterprise), all can be used in your enterprise stably and freely.

## Startup command
Switch into server directory fisrt.
Windows: sbin/startup.bat
Linux/Termux: sbin/mesh.sh start
Android: Hit the startup button.

## API definition
Each api definition is a json, it looks like:
```Json
{
    "name": "queryCustomer",
    "method":"GET",
    "property" : "private",
    "tokenChecker" : "USER",

    "request": [
        {"name":"id", "type":"int", "must":true, "min":1, "comment":"Customer ID"}
    ],

    "process" : [
        {
            "name" : "get_customer_detail",
            "type" : "rdb",
            "db": "crm",
            "sqls" : [
                {
                    "name":"detail",
                    "metas" : "each",
                    "multi":false,
                    "merge":true,
                    "sql":"select c.name,c.address,c.createAt,c.creator,c.taxid,c.flowid,
                                  c.flSta 'status',c.business,c.cmt 'comment',c.ordNum,p.power
                          from customers c, power p
                          where c.id=@{id} and p.account='@{#tokenAcc}' and p.type='CU'
                          and p.did=@{id} and p.endT>@{NOW|unit60000}"
                }
            ]
        }
    ],
    "response": {
        "check":false,
        "segments":[
            {"name":"name", "type":"string", "comment":"customer name"},
            {"name":"createAt", "type":"int", "comment":"create time by utc minutes"},
            {"name":"creator", "type":"string", "comment":"creator"},
            {"name":"taxid", "type":"string", "comment":"uni-code of customer's enterprise"},
            {"name":"flowid", "type":"int", "comment":"workflow id"},
            {"name":"status", "type":"int", "comment":"status"},
            {"name":"business", "type":"string", "comment":"main business"},
            {"name":"address", "type":"string", "comment":"address"},
            {"name":"comment", "type":"string", "comment":"extended information, a json string"}
        ]
    }
}
```
If string in the config is very long, newline can exist in it. Such as 'sql' in fore example.

Many apis can be written in one config file, for example in file customer.cfg:
```Json
[
{
    "name": "get",
    "method":"GET",
    "property" : "private",
    "tokenChecker" : "USER",
    ...
},
{
    "name": "add",
    "method":"GET",
    "property" : "private",
    "tokenChecker" : "USER",
    ...
},
...
]
```
Request in clients with url "/api/customer/add","/api/customer/get"...

## Advanced API development
It supports JS to realize complex logics, such as:
```Json
{
    "name": "setConfig",
    "property" : "private",
    "method": "PUT",
    "tokenChecker": "MNT",
    "comment":"设置webdb实例的配置信息，一个实例只能设置一条，但是cfg中可以有多个db",

    "request": [
        {"name":"addr", "type":"ip", "must":true, "format":"V4|LAN|PORT", "comment":"内网IP+端口号"},
        {"name":"cfg", "type":"string", "must":true, "comment":"配置信息，一个list形式的json体字符串"}
    ],

    "process" : [
        {
            "name" : "check_cfg",
            "type" : "js",
            "script":"(function() {
                var jsonCfg=@{cfg};
                for(var d of jsonCfg) {
                    if(isNaN(parseInt(d.no))) {
                        return Mesh.error(RetCode.WRONG_PARAMETER, 'invalid no ' + d.no);
                    }
                    var start=parseInt(d.shardStart);
                    var end=parseInt(d.shardEnd);
                    if(isNaN(start) || start<0 || isNaN(end) || end>32768) {
                        return Mesh.error(RetCode.WRONG_PARAMETER, 'invalid shardStart or shardEnd of ' + d.no);
                    }
                    if(start>=end) {
                        return Mesh.error(RetCode.WRONG_PARAMETER, 'shardStart>=shardEnd of ' + d.no);
                    }
                }
                return Mesh.success({});
            })()"
        },
        {
            "name" : "set_db_config",
            "type" : "biosmeta",
            "actions": [
                {"action":"put", "key":"/service/webdb/dbs/@{addr}", "value":"@{cfg}"}
            ]
        }
    ],
    "response":[]
}
```
JS has many usages, you can refer to examples in [Enterpris](https://github.com/ZhiJianMesh/endterprise).
If you want to know more about it, refer to [HomePage](http://www.zhijian.net.cn/).