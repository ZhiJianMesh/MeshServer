const EMPTY_STEP={step:'',type:'S',name:'',ext:'{}',cmt:'',signer:''};
const SERVICE_WF="workflow";
const BASE_CHANGED=1;
const STEP_CHANGED=2;

const _defaultFlowTags = {
    changeNotSaved:"修改的内容尚未保存，请确认是否放弃修改？",
    cmt:'工作流描述',
    dispName:'显示名称',
    types:{S:'单人签字',M:'多人会签'},
    modify:'修改',
    ok:'确定',
    cancel:'取消',

    step:{
      no:'No',
      step:"序号",
      name:"名称",
      type:"签名",
      ext:"扩展",
      signer:"默认权签人",
      comment:"描述"
    }
}
//简易的用户&群组管理
export default {
props: {
    service:{type:String,required:true},
    flowTags:{type:Object, required:false},
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
    tags:{},
    curFlow:{name:'',cmt:'',dispName:''},
    steps:[],
    changed:0,
    
    flowOpts:[], //工作流选项
    typeOpts:[], //步骤类型选项
    
    stepCtrl:{dlg:false,dta:{},no:-1/*待编辑的step，-1表示新增*/}
}},
created(){
    if(this.flowTags&&Object.keys(this.flowTags).length>0) {
        copyObjTo(_defaultFlowTags, this.tags);
        copyObjTo(this.flowTags, this.tags);
    } else {
        this.tags=_defaultFlowTags;
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

    request({method:"GET", url:"/settings/list?service="+this.service}, SERVICE_WF).then(resp=>{
        if(resp.code != RetCode.OK) {
            this.flowOpts=[];
            this.flow_changed(0);
            return;
        }
        for(var f of resp.data.list) {
            this.flowOpts.push({label:f.dispName,value:f.name});
        }
        var f=resp.data.list[0];
        this.curFlow={name:f.name, cmt:f.cmt,id:f.id};
        this.get_flow_info(this.curFlow.name);
    })
},
show_step_detail(i) {
    copyObjTo(this.steps[i], this.stepCtrl.dta);
    this.stepCtrl.dlg=true;
    this.stepCtrl.no=i;
},
remove_step(n){
    this.steps.splice(n,1);
    for(var i in this.steps) {
        this.steps[i].step=i;
    }
    this.flow_changed(STEP_CHANGED);
},
confirm_step(){
    this.stepCtrl.dta.type_s=this.tags.types[this.stepCtrl.dta.type];
    if(this.stepCtrl.no<0) {//add
        this.steps.push(cloneObj(this.stepCtrl.dta));
        copyObjTo(EMPTY_STEP, this.stepCtrl.dta);
    } else { //modify
        copyObjTo(this.stepCtrl.dta, this.steps[this.stepCtrl.no]);
    }
    this.steps.sort((a,b)=>{
        return a.step-b.step;
    });
    for(var i in this.steps) {
        this.steps[i].step=i;
    }
    
    this.stepCtrl.dlg=false;
    this.flow_changed(STEP_CHANGED);
},
open_add_step(){
    var max=-1;
    for(var i in this.steps) {
        var v=parseInt(this.steps[i].step)
        if(max < v) {
            max = v;
        }
    }
    copyObjTo(EMPTY_STEP, this.stepCtrl.dta);
    this.stepCtrl.dta.step=max+1;
    this.stepCtrl.dta.type=this.typeOpts[0].value;
    this.stepCtrl.dlg=true;
    this.stepCtrl.no=-1;
},
save(){
    if((this.changed&BASE_CHANGED) !=0) {
        var opts={method:"PUT", url:"/settings/update", data:{
            service:this.service, name:this.curFlow.name,
            dispName:this.curFlow.dispName, cmt:this.curFlow.cmt}};
        request(opts, SERVICE_WF).then(resp=>{
            if(resp.code != RetCode.OK) {
                this.alertDlg.showErr(resp.code, resp.info);
                return;
            }
            this.flow_changed(-BASE_CHANGED);
        });
    }
    if((this.changed&STEP_CHANGED) !=0) {
        var opts={method:"POST", url:"/settings/saveSteps", data:{
            service:this.service, name:this.curFlow.name,
            dispName:this.curFlow.dispName, steps:this.steps}};
        request(opts, SERVICE_WF).then(resp=>{
            if(resp.code != RetCode.OK) {
                this.alertDlg.showErr(resp.code, resp.info);
                return;
            }
            this.flow_changed(-STEP_CHANGED);
        });
    }
},
get_flow_info(flow) {
    var opts={method:"GET",url:"/settings/getInfoByName?name="+flow+"&service="+this.service};
    request(opts, SERVICE_WF).then(resp=>{
        if(resp.code != 0) {
            return;
        }
        this.curFlow.name=flow;
        copyObjTo(resp.data, this.curFlow, ['dispName','cmt','id'])
        this.steps=resp.data.steps.map(s => {
            s.type_s=this.tags.types[s.type];
            return s;
        });
        this.flow_changed(0);
    })
},
change_flow() {
    if(this.changed!=0) {
        this.confirmDlg.show(this.tags.changeNotSaved, ()=>{
            this.get_flow_info(this.curFlow.name);
        });
    } else {
        this.get_flow_info(this.curFlow.name);
    }
},
flow_changed(chgFlg) {
    if(chgFlg==0) this.changed=0;
    else if(chgFlg>0) this.changed |= chgFlg;
    else this.changed &= ~(-chgFlg);
    this.$emit('update:modelValue', {changed:this.changed!=0,
        name:this.curFlow.name, size:this.flowOpts.length});
}
},
template:`
<q-select v-model="curFlow.name" :options="flowOpts" outlined dense
 @update:model-value="change_flow" emit-value map-options>
</q-select>
<q-input :label="tags.dispName" v-model="curFlow.dispName" maxlength=120
 @update:model-value="flow_changed(1)"></q-input>
<q-input :label="tags.cmt" v-model="curFlow.cmt" maxlength=120
 @update:model-value="flow_changed(1)"></q-input>
<q-list>
<q-item>
 <q-item-section side><q-item-label caption>{{tags.step.step}}</q-item-label></q-item-section>
 <q-item-section><q-item-label caption>{{tags.step.name}}</q-item-label></q-item-section>
 <q-item-section><q-item-label caption>{{tags.step.type}}/{{tags.step.signer}}</q-item-label></q-item-section>
 <q-item-section avatar><q-icon name="add_circle" color="primary" @click="open_add_step"></q-icon></q-item-section>
</q-item>
<q-item v-for="(s,i) in steps" clickable @click="show_step_detail(i)">
 <q-item-section side>{{s.step}}</q-item-section>
 <q-item-section>
  <q-item-label>{{s.name}}</q-item-label>
  <q-item-label caption>{{s.cmt}}</q-item-label>
 </q-item-section>
 <q-item-section>
  <q-item-label>{{s.type_s}}</q-item-label>
  <q-item-label caption>{{s.signer}}</q-item-label>
 </q-item-section>
 <q-item-section avatar><q-icon name="cancel" color="green" @click.stop="remove_step(i)"></q-icon></q-item-section>
</q-item>
</q-list>

<!-- 增加/修改/显示步骤信息弹窗 -->
<q-dialog v-model="stepCtrl.dlg" no-backdrop-dismiss>
  <q-card style="min-width:70vw">
   <q-card-section class="q-pt-none">
    <q-list>
      <q-item><q-item-section>
        <q-input :label="tags.step.step" v-model.num="stepCtrl.dta.step" dense></q-input>
      </q-item-section></q-item>
      <q-item><q-item-section>
        <q-input :label="tags.step.name" v-model="stepCtrl.dta.name" maxlength=80 dense></q-input>
      </q-item-section></q-item>
      <q-item><q-item-section>
        <q-input :label="tags.step.signer" v-model="stepCtrl.dta.signer" maxlength=80 dense></q-input>
      </q-item-section></q-item>
      <q-item><q-item-section>
        <q-select v-model="stepCtrl.dta.type" :options="typeOpts" :label="tags.step.type"
         emit-value map-options></q-select>
      </q-item-section></q-item>
      <q-item><q-item-section>
        <q-input :label="tags.step.comment" v-model="stepCtrl.dta.cmt" type="text"
         autogrow maxlength=100 dense></q-input>
      </q-item-section></q-item>
    </q-list>
   </q-card-section>
   <q-card-actions align="right">
    <q-btn :label="tags.ok" color="primary" @click.stop="confirm_step"></q-btn>
    <q-btn flat :label="tags.cancel" color="primary" v-close-popup></q-btn>
   </q-card-actions>
  </q-card>
</q-dialog>
`
}