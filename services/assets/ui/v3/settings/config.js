//按模板定义，解析扩展字段
//模板格式为[
// {k:字段名(字母、数字或下划线),
//  n:名称(根据语言不同输入不同内容，只支持一种语言),
//  t:字段类型('s':'文字','n':"数值",'d':'日期')
// },...]
//扩展内容：本质为一个json对象，格式为
//{k1:v1,k2:v2...}，其中的k1、k2就是模板中定义的字段名k
function decodeExt(extVal,tmpl) {
    var o;
    var ext=[];
    var t,v;
    if(extVal !== null && typeof extVal === "object") {
        o = extVal;
    } else {
        try{o=JSON.parse(extVal);}catch(err){}
    }
    for(var k in tmpl) {//字段以模板中为准，{a:{n:xxx,t:s/n/d/b},b:{...}...}
        t=tmpl[k];
        v=o[k];
        if(!v) {
          if(t.t=='b') {
            v=false; //vue3不必用this.$set方法
          } else {
            v='';
          }
        }
        ext.push({n:t.n, v:v, t:t.t, k:k});
    }
    return ext;
}

//打包扩展字段
//ext的格式为：[{k:k,v:yyy,n:xxx,t:n/s/d}...] => {k:yyy}
function encodeExt(ext) {
    var dta={};
    for(var e of ext) {
      dta[e.k]=e.v;
    }
    return JSON.stringify(dta);
}

export {decodeExt, encodeExt};

//公司级服务配置，比如icrm的客户信息扩展字段的模板
const _defaultCfgTags = {
    changeNotSaved:"修改的内容尚未保存，请确认是否放弃修改？",
    needAz:'字段必须是a-z、A-Z字符的组合',
    segTypes:{'s':'文字','n':"数值",'d':'日期'},

    asMap:'多字段模板格式',
    segKey:'字段',
    segName:"名称",
    segType:"类型",
    val:'配置内容'
}

export default {
props: {
    service:{type:String,required:true},
    item:{type:String,required:false,default:''},
    cfgTags:{type:Object, required:false},
    showMap:{type:Boolean,required:false},
    confirmDlg:{type:Object, required:true},
    alertDlg:{type:Object, required:true}
},
watch: {
  service(_n,_o) {
    this.init();
  }
},
emits: ['update:modelValue'],
data() {return {
    chged:0,
    segTypes:[],
    cfgOpts:[],
    cfgs:[], //配置列表K-V
    cur:{k:'',v:'',tmpl:{},asMap:false/*多字段模板方式*/}, //字符串形式或map形式
    newSeg:{k:'', n:'', t:'s'},
    tags:{}
}},
created(){
    if(this.cfgTags&&Object.keys(this.cfgTags).length>0) {
        copyObjTo(_defaultCfgTags, this.tags);
        copyObjTo(this.cfgTags, this.tags);
    } else {
        this.tags=_defaultCfgTags;
    }
    this.segTypes=Object.entries(this.tags.segTypes).map(([k,v])=>{return {value:k,label:v}})
    this.init();
},
methods:{
init() {
    if(!this.service)return;

    if(!this.item) {
        var url="/settings/list?service="+this.service;
        return request({method:"GET",url:url}, SERVICE_CONFIG).then((resp)=>{
            if(resp.code!=RetCode.OK) {
                this.changed(false);
                this.cfgOpts=[];
                this.cfgs=[];
                return this.cur.asMap?{}:'';
            }
            this.cfgs=resp.data.cfgs;
            this.cfgOpts=resp.data.cfgs.map((c,i)=>{return {value:i,label:c.k}});
            this.setCur(0);
        })
    } else { //设置了item，则只配置一个配置项
        var url="/settings/get?service="+this.service+"&k="+this.item;
        return request({method:"GET",url:url}, SERVICE_CONFIG).then((resp)=>{
            if(resp.code!=RetCode.OK) {
                this.cur=this.parse(this.item, "");
            } else {
                this.cur=this.parse(this.item, resp.data[this.item]);
            }
        })
    }
},
rmv_tpl_seg(k){
    delete this.cur.tmpl[k];
    this.changed(true);
},
add_tpl_seg(){
    if(!/^[a-zA-Z]{1,30}$/.test(this.newSeg.k)) {
        this.alertDlg.show(this.tags.needAz);
        return;
    }
    this.cur.tmpl[this.newSeg.k]={n:this.newSeg.n,t:this.newSeg.t};
    this.changed(true);
    this.newSeg={k:'', n:'', t:'s'};
},
save(){
    var v=this.cur.asMap?JSON.stringify(this.cur.tmpl):this.cur.v;
    var opts={url:"/settings/put",method:"PUT",data:{service:this.service,k:this.cur.k,v:v}};
    return request(opts, SERVICE_CONFIG).then(resp=>{
        if(resp.code != 0) {
            this.alertDlg.showErr(resp.code, resp.info);
            return false;
        }
        this.changed(false);
        return true;
    })
},
change_cfg(i) {
    if(this.chged!=0) {
        this.confirmDlg.show(this.tags.changeNotSaved, ()=>{
            this.setCur(i);
        });
    } else {
        this.setCur(i);
    }
},
setCur(i) {
    var c=this.cfgs[i];
    this.cur = this.parse(c.k, c.v);
    this.newSeg={k:'', n:'', t:'s'};
    this.changed(false);
},
parse(k,v) {
    var asMap=v.startsWith('{')&&v.endsWith('}');
    if(asMap) {
        return {k:k,v:'',tmpl:JSON.parse(v),asMap:true};
    }
    asMap=this.showMap!==undefined?this.showMap:false;
    return {k:k,v:v,tmpl:{},asMap:asMap};
},
changed(chged) {
    this.chged=chged;
    this.$emit('update:modelValue', {changed:chged,
        name:this.cur.k,size:this.cfgs.length});
}
},
template:`
<div class="row">
 <div class="col-8">
  <q-select v-model="cur.k" :options="cfgOpts" outlined dense
   @update:model-value="change_cfg" emit-value map-options
   v-if="!item"></q-select>
  <div v-else>{{item}}</div>
 </div>
 <div class="col-4">
  <q-checkbox v-model="cur.asMap" :label="tags.asMap"></q-checkbox>
 </div>
</div>
<div v-if="cur.asMap">
 <q-list>
 <q-item>
  <q-item-section><q-item-label caption>{{tags.segKey}}</q-item-label></q-item-section>
  <q-item-section><q-item-label caption>{{tags.segName}}</q-item-label></q-item-section>
  <q-item-section><q-item-label caption>{{tags.segType}}</q-item-label></q-item-section>
  <q-item-section avatar></q-item-section>
 </q-item>
 <q-item v-for="(tpl,k) in cur.tmpl">
  <q-item-section>{{k}}</q-item-section>
  <q-item-section>{{tpl.n}}</q-item-section>
  <q-item-section>{{tags.segTypes[tpl.t]}}</q-item-section>
  <q-item-section avatar><q-icon name="cancel" color="green" @click="rmv_tpl_seg(k)"></q-icon></q-item-section>
 </q-item>
 <q-item>
  <q-item-section>
   <q-input v-model="newSeg.k" :label="tags.segKey"></q-input>
  </q-item-section>
  <q-item-section>
   <q-input v-model="newSeg.n" :label="tags.segName"></q-input>
  </q-item-section>
  <q-item-section>
   <q-select v-model="newSeg.t" :options="segTypes" emit-value map-options></q-select>
  </q-item-section>
  <q-item-section avatar><q-icon name="add_circle" color="primary" @click="add_tpl_seg()"></q-icon></q-item-section>
 </q-item>
 </q-list>
</div>
<div v-else>
 <q-input v-model="cur.v" :label="tags.val"
  @update:model-value="changed(true)"></q-input>
</div>
`
}