const SERVICE_SCH="schedule";

const _defaultSchTags = {
    types:{D:'每天',W:'每周',M:'每月',Y:'每年',P:'固定周期'},
    sync:{Y:'同步',N:'异步'},
    
    ok:'确定',
    cancel:'取消',

    name:'名称',
    type:'周期类型',
    val:'周期偏移(分钟)',
    url:'接口URL',
    maxRetry:'最大重试次数',
    minTime:'最小重试间隔(分钟)'
}
//简易的用户&群组管理
export default {
props: {
    service:{type:String, required:true},
    schTags:{type:Object, required:false},
    alertDlg:{type:Object, required:true}
},
watch: {
  service(_n,_o) {
    this.init();
  }
},
emits: ['update:modelValue'],
data() {return {
    tags:{},
    curSch:{},
    list:[], //id,name,service,type,val,url,maxRetry,minTime

    schOpts:[], //定时任务选项
    typeOpts:[], //周期类型选项
    
    ctrl:{dlg:false,dta:{},no:-1/*待编辑的定时任务*/}
}},
created(){
    if(this.schTags&&Object.keys(this.schTags).length>0) {
        copyObjTo(_defaultSchTags, this.tags);
        copyObjTo(this.schTags, this.tags);
    } else {
        this.tags=_defaultSchTags;
    }
    for(var k in this.tags.types) {
        var v=this.tags.types[k];
        this.typeOpts.push({label:v, value:k});
    }
    this.init();
},
methods:{
init() {
    if(!this.service)return;

    request({method:"GET", url:"/settings/list?service="+this.service}, SERVICE_SCH).then(resp=>{
        if(resp.code != RetCode.OK) {
            this.list=[];
            this.schOpts=[];
            this.changed();
            return;
        }
        var opts=[];
        var list=[];
        var offset=new Date().getTimezoneOffset();
        for(var i in resp.data.list) {
            var l=this.fmt_sch(resp.data.list[i], offset);
            opts.push({value:i, label:l.name});
            list.push(l);
        }
        this.list=list;
        this.schOpts=opts;
        this.change_sch(0);
    });
},
save(){
    var dta=cloneObj(this.ctrl.dta);
    var offset=new Date().getTimezoneOffset();
    dta.val+=offset;
    request({method:"PUT", url:"/settings/update", data:dta}, SERVICE_SCH).then(resp=>{
        if(resp.code != RetCode.OK) {
            this.alertDlg.showErr(resp.code, resp.info);
            return;
        }
        this.ctrl.dlg=false;
        this.list[this.ctrl.no]=this.fmt_sch(dta, offset);
        this.curSch=this.list[this.ctrl.no];
        this.changed();
    })
},
fmt_sch(l, offset) {
    l.type_s=this.tags.types[l.type];
    l.sync_s=this.tags.sync[l.sync];
    if(l.type!='P') l.val-=offset;
    return l;
},
change_sch(i) {
    this.curSch=this.list[i];
    this.ctrl.no=i;
    this.changed();
},
changed() {
    this.$emit('update:modelValue', {changed:false,
        name:this.curSch.name, size:this.list.length});
},
show_edit() {
    this.ctrl.dta=copyObjExc(this.curSch, ["type_s","sync_s"]);
    this.ctrl.dlg=true;
}
},
template:`
<div class="row">
 <div class="col-10">
  <q-select v-model="curSch.name" :options="schOpts" outlined dense
   @update:model-value="change_sch" emit-value map-options></q-select>
 </div>
 <div class="col-2 text-right">
  <q-btn icon="edit" flat dense color="primary" @click="show_edit"></q-btn>
 </div>
</div>
<q-list>
 <q-item>
  <q-item-section>{{tags.name}}</q-item-section>
  <q-item-section side>{{curSch.name}}</q-item-section>
 </q-item>
 <q-item>
  <q-item-section>{{tags.type}}</q-item-section>
  <q-item-section side>{{curSch.type_s}}</q-item-section>
 </q-item>
 <q-item>
  <q-item-section>{{tags.val}}</q-item-section>
  <q-item-section side>{{curSch.val}}</q-item-section>
 </q-item>
 <q-item>
  <q-item-section>{{tags.url}}</q-item-section>
  <q-item-section side>{{curSch.url}} {{curSch.sync_s}}</q-item-section>
 </q-item>
 <q-item>
  <q-item-section>{{tags.maxRetry}}</q-item-section>
  <q-item-section side>{{curSch.maxRetry}}</q-item-section>
 </q-item>
 <q-item>
  <q-item-section>{{tags.minTime}}</q-item-section>
  <q-item-section side>{{curSch.minTime}}</q-item-section>
 </q-item>
</q-list>

<!-- 修改定时任务信息弹窗 -->
<q-dialog v-model="ctrl.dlg" persistent>
  <q-card style="min-width:70vw">
    <q-card-section class="q-pt-none">
    <q-list>
     <q-item><q-item-section>
      <q-input :label="tags.name" v-model="ctrl.dta.name" dense></q-input>
     </q-item-section></q-item>
     <q-item><q-item-section>
      <q-select v-model="ctrl.dta.type" :options="typeOpts" :label="tags.type"
       emit-value map-options></q-select>
     </q-item-section></q-item>
     <q-item><q-item-section>
      <q-input :label="tags.val" v-model.number="ctrl.dta.val" dense></q-input>
     </q-item-section></q-item>
     <q-item><q-item-section>
      <q-input :label="tags.url" v-model="ctrl.dta.url" dense></q-input>
     </q-item-section></q-item>
     <q-item><q-item-section class="q-gutter-sm">
      <q-radio v-model="ctrl.dta.sync" val="Y" :label="tags.sync.Y"></q-radio>
      <q-radio v-model="ctrl.dta.sync" val="N" :label="tags.sync.N"></q-radio>
     </q-item-section></q-item>
     <q-item><q-item-section>
      <q-input :label="tags.maxRetry" v-model.number="ctrl.dta.maxRetry" dense></q-input>
     </q-item-section></q-item>
     <q-item><q-item-section>
      <q-input :label="tags.minTime" v-model.number="ctrl.dta.minTime" dense></q-input>
     </q-item-section></q-item>
    </q-list>
    </q-card-section>
    <q-card-actions align="right">
      <q-btn :label="tags.ok" color="primary" @click.stop="save"></q-btn>
      <q-btn flat :label="tags.cancel" color="primary" v-close-popup></q-btn>
    </q-card-actions>
  </q-card>
</q-dialog>
`
}