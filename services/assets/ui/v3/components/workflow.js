import UserSelector from "./user_selector.js"

function sta2icon(s){//工作流状态转图标
  if(s==0) {
    return 'star_border';
  }
  if(s==100) {
    return 'star';
  }
  return 'star_half';
}
export {sta2icon};

const _wf_defaultTags = {
    wrongWfDef:"工作流定义错误",
    wfClkSuccess:'执行成功!',
    unHandled:'未处理',
    result:'执行结果',
    descr:'概要',
    
    opinion:"意见",
    agree:'同意',
    disAgree:'不同意',
    finish:"完成",
    reject:"返回",
    cancel:'取消',
    nextStep:"下一步",
    signers:'权签人',

    errMsgs:{ //工作流本身的错误码
      '10104':"会签仍未结束",
      '10106':"输入的帐号不符合要求",
      '10108':"下一步会签，参与会签的人不可以有自己",
      '10109':"下一步只可指定一个责任人",
      '10110':"必须指定下一步责任人",
      '10111':'工作流未定义',
      '10112':'工作流数据记录错误',
      '10113':'存在未完成的工作',
      '10114':'只有处于第一步的工作流才可以取消'
    }
}

const _WF_={
flowInfos:{},
service:"workflow",
//不能用箭头函数，否则不能用this，箭头函数的this是在运行时所在的对象
//同时获取工作流的定义
flowDef:function(id){
    if(this.flowInfos[id]) {
        return new Promise(resolve=>{resolve(this.flowInfos[id])});
    }
    var url="/api/flow/info?flowid="+id;
    return request({method:"GET",url:url}, this.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            return {};
        }
        //flow:{id,name,dispName,maxStep,cmt,reject,confirm,dtlApi}
        //steps:{flow,step,type,name,ext,cmt,signer}
        resp.data.steps.forEach(s=> {
            s.title=s.name+'('+s.cmt+')';
        })
        this.flowInfos[id]=resp.data;
        return resp.data;
    })
},
formDtlData:function(dta, segments){
    var dtl=[];
    var dt=new Date();
    segments.forEach(s => { //{t:type,n:segmentName,s:tag,f:function(v){}}
        var v;
        if(s.t=='dt') {
            dt.setTime(dta[s.n]*60000); //时间都用分钟
            v=datetime2str(dt);
        } else if(s.t=='d') {
            dt.setTime(dta[s.n]*60000);
            v=date2str(dt);
        } else if(s.t=='f') {
            v=s.f(dta[s.n]);
        } else {/*(s.t=='s'||s.t=='n')*/
            v=dta[s.n];
        }
        dtl.push({k:s.s,v:v});
    });
    return dtl;
},
remove:function(flowid, did, service) {
    //工作流数据错乱的情况下，业务数据不存在，工作流发起人可以删除工作流记录
    var rmvUrl = appendParas("/removeByOwner", {flowid:flowid,did:did,service:service});
    return request({method:"DELETE", url:rmvUrl}, this.service);
},
showPage:function(flowid,did,router) {
    this.flowDef(flowid).then(fd=>{
        var pos=fd.wfPage.indexOf('=>');
        var url,page;
        if(pos<0) {
            url=fd.wfPage;
        } else {
            url=fd.wfPage.substring(0, pos);
            page=fd.wfPage.substring(pos + 2);
            router.addRoute({path:url, component:()=>import(page)});
        }
        router.push(appendParas(url,{flow:flowid, did:did, service:fd.service}));
    })
}
}
export {_WF_};

export default {
components:{
    "user-selector":UserSelector
},
data() {return {
    flow:{},//流程定义信息{service,name,dispName,maxStep,steps}
    base:{creator:'',createAt:0,step:0,descr:''},
    dtl:{}, //数据详情
    steps:[],//每一步的数据，并非定义
    opinion:'',//处理意见
    nextSigners:[],//下一步处理人，如果指定了默认处理人，则不显示输入框
    allDone:true,
    oIcons:{P:'thumb_up',R:'thumb_down'},
    tags:{},
}},
props: {
    service:{type:String, required:true}, //所属服务的名称
    flowid:{type:Number, required:true},
    did:{type:Number, required:true},
    alertDlg:{type:Object, required:true},
    flowTags:{type:Object, required:false} //工作流的tags，默认为_wf_defaultTags
},
emits: ['update:modelValue'],
created(){
    _WF_.flowDef(this.flowid).then(sd=>{
        this.flow=sd;
        this.query_opinions();
    });
    if(this.flowTags&&Object.keys(this.flowTags).length>0) {
        copyObjTo(_wf_defaultTags, this.tags);
        copyObjTo(this.flowTags, this.tags);
    } else {
        this.tags=_wf_defaultTags
    }
},
methods:{
query_opinions() {
    this.opinion='';
    this.nextSigners=[];
    var url="/api/opinions?flowid="+this.flowid+"&did="+this.did+"&service="+this.service;
    request({method:"GET",url:url}, _WF_.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            return;
        }
        var curStep=resp.data.step;
        this.base.step=curStep; //待处理的步骤
        this.base.nextStepType=resp.data.nextStepType; //下一步类型，用于决定帐号输入是单选还是多选
        this.base.creator=resp.data.creator;
        this.base.descr=resp.data.descr;
        var dt=new Date();
        dt.setTime(resp.data.update_time);
        this.base.createAt=datetime2str(dt);
        if(curStep<this.flow.maxStep) { //查询当前步骤的默认权签人
            var nextSigner=this.flow.steps[resp.data.nextStep].signer;//工作流定义中指定的权签人
            this.get_next_signers(nextSigner,curStep).then((hasSigner)=>{
                this.init_steps(resp.data, curStep, hasSigner);
            });
        } else {
            this.init_steps(resp.data, curStep, false);
        }
        this.$emit('update:modelValue', curStep);
    });
},
init_steps(data, curStep, hasSigner) {
    var signer=data.signer;//当前查看人
    var steps=[]; //预填充步骤的数据
    this.flow.steps.forEach(o=>{
        steps[o.step]={step:o.step,title:o.title,ext:o.ext,
            type:o.type,//类型：O-ne单签，M-ulti会签
            t:0,/*时间戳*/ts:'',//时间
            list:[],//当前帐号在当前步骤的意见列表
            olist:[]//其他人的意见
        }
    });

    var dt=new Date();
    var cols=data.cols;
    var step, ts;
    var allDone=true;//会签人是否都反馈意见
    //step,opinion,result,type,signer,turn,update_time
    for(var l of data.opinions) {
        var o={};
        var j=0;
        for(var c in cols) {//字段名在cols中，数据不带字段名
            o[cols[c]]=l[j++];
        }
        dt.setTime(o.update_time);
        ts=datetime2str(dt);
        if(!(step=steps[o.step])){
            continue;
        }
        if(step.t<o.update_time){//标题上只显示最后一次时间
            step.t=o.update_time;
            step.ts=ts;
        }
        if(o.signer==signer) {//当前处理人的意见
            step.list.push({signer:o.signer,time:ts,opinion:o.opinion,
             result:o.result,turn:o.turn,type:o.type,step:o.step});
            if(curStep==o.step&&o.result=='I') {
                step.hasSigner=hasSigner; //是否有默认权签人
                if(step.ext!='') {
                    //附加参数解析{page:xxx,tag:yyy}，用于处理特殊功能。
                    //比如采购中设置采购价，或者确认采购清单等，支持button、page两种
                    //在过程初始化中需要设置好ext，并在language中添加相应的语言标签
                    var ext=JSON.parse(step.ext);
                    ext.tag=this.tags[ext.tag];
                    if(ext.page) {
                        ext.page=appendParas(ext.page,{flowid:this.flowid,did:this.did,step:curStep});
                    } else if(ext.button) {
                        ext.button=appendParas(ext.button,{flowid:this.flowid,did:this.did,step:curStep});
                    }
                    step.ext=ext;
                }
            }
        } else {
            step.olist.push({signer:o.signer,time:ts,
            opinion:o.result=='I'?this.tags.unHandled:o.opinion,
            result:o.result,turn:o.turn,type:o.type,step:o.step});
            if(curStep==o.step && step.type=='M') {//会签中，所有从签人都处理完毕才能向下走
                if(o.result=='I') {
                    allDone=false;
                }
            }
        }            
    }
    this.allDone=allDone;
    this.steps=steps;
},
get_next_signers(signer,step) { //请求默认的处理人，如果存在，则不显示下一步权签人输入
    if(!signer)return new Promise(resolve=>resolve(false));
    var url,service;
    if(/^\d+$/.test(signer)) {//步骤号
        service=_WF_.service;
        url=appendParas("/stepSigners",{flowid:this.flowid,did:this.did,service:this.service,step:signer});
    } else if(/^\/.+/.test(signer)){//一个url，调用后获得signer
        service=this.service;
        url=appendParas(signer,{flowid:this.flowid,did:this.did,step:step});
    } else { //具体的帐号
        return new Promise(resolve=>{
            this.nextSigners=[signer];
            resolve(false)
        })
    }
    return request({method:"GET", url:url}, service).then(resp=>{
        if(resp.code==RetCode.OK) {
            this.nextSigners=resp.data.signers
            return true;
        }
        return false;
    })
},
confirm() {
    if(this.base.step>this.flow.maxStep) {
        this.alertDlg.show(this.tags.wrongWfDef);
        return;
    }
    var url="/api/confirm";
    var data={flowid:this.flowid, did:this.did,service:this.service,
        opinion:this.opinion, nextSigners:this.nextSigners};
    request({method:"POST",url:url, data:data}, _WF_.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.showErr(resp);
            return;
        }
        this.base.step=resp.data.nextStep;
        this.$emit('update:modelValue', this.base.step);
        this.query_opinions();
    })
},
counterSign(agree) {//多人会签，不会向下一步走，等待主签人决定(上一步责任人)
    var url="/api/counterSign";
    var data={flowid:this.flowid, did:this.did,service:this.service,
        opinion:this.opinion, result:agree?'P':'R'};
    request({method:"POST",url:url,data:data}, _WF_.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.showErr(resp);
            return;
        }
        this.query_opinions();
    })
},
reject() {
    if(this.base.step<=0) {
        return;
    }
    var url="/api/reject";
    var data={flowid:this.flowid, did:this.did, opinion:this.opinion, service:this.service};
    request({method:"POST",url:url, data:data}, _WF_.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.showErr(resp);
            return;
        }
        this.base.step=resp.data.foreStep;
        this.$emit('update:modelValue', this.base.step);
        this.query_opinions();
    })
},
cancel() { //取消
    if(this.base.step!=0) return;
    var url="/api/cancel?flowid="+this.flowid+"&did="+this.did+"&service="+this.service;
    request({method:"DELETE", url:url}, _WF_.service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.showErr(resp);
            return;
        }
        this.$router.back();
    })
},
btn_clk(api) {//ext中的button点击事件
    var idx=api.indexOf('@');
    var service=api.substring(0,idx);
    var url=api.substring(idx+1);
    request({method:"GET",url:url}, service).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.showErr(resp);
        } else {
            this.alertDlg.show(this.tags.wfClkSuccess, this.tags.result);
        }
    });
},
showErr(resp) {
    if(resp.code>=10104&&resp.code<=10114) {
        this.alertDlg.showErr(resp.code, resp.info, this.tags.errMsgs);
    } else {
        this.alertDlg.showErr(resp.code, resp.info, this.tags.result);
    }
},
goto(url) {
    this.$router.push(url);
}
},
template:`
<q-timeline color="secondary">
<q-timeline-entry v-for="s in steps" :title="s.title" :subtitle="s.ts"
 :color="s.step==base.step?'orange':'primary'">

<q-btn v-if="s.ext.page" color="primary" :label="s.ext.tag"
  @click="goto(s.ext.page)" dense></q-btn>
<q-btn v-if="s.ext.button" color="primary" :label="s.ext.tag"
  @click="btn_clk(s.ext.button)" dense></q-btn>

<q-list dense>
 <q-item v-for="o in s.list"><!-- 自己的意见 -->
  <q-item-section>
   <q-item-label>{{o.signer}}</q-item-label>
   <q-item-label caption>
    <div v-if="o.result=='I' && o.step==base.step">
     <q-input v-model="opinion" :label="tags.opinion" outlined dense maxlength=100></q-input>
     <div v-if="o.type!='S'"><!-- 会签时的从签不必设置下一步责任人,O/S/M -->
      <user-selector :label="tags.signers" :multi="base.nextStepType=='M'"
       :accounts="nextSigners" :useid="false"
       v-if="s.step!=flow.maxStep && !s.hasSigner"></user-selector>
      <div class="row justify-end q-mt-lg">
       <q-btn @click="confirm" color="primary" :disable="!allDone"
        :label="s.step!=flow.maxStep?tags.nextStep:tags.finish" dense></q-btn>
       <q-btn v-if="s.step==0" @click="cancel" color="primary"
        :label="tags.cancel" class="q-ml-sm" flat dense></q-btn>
       <q-btn v-else @click="reject" color="primary"
        :label="tags.reject" class="q-ml-sm" flat dense></q-btn>
      </div>
     </div>
     <div v-else class="row justify-end q-mt-lg"> <!-- 会签时从签人发表意见后，不会向下一步走 -->
      <q-btn @click="counterSign(true)" color="primary" :label="tags.agree" dense></q-btn>
      <q-btn flat @click="counterSign(false)" color="primary" :label="tags.disAgree" class="q-ml-sm" dense></q-btn>
     </div>
    </div>
    <div v-else> <!-- o.result!='I' -->
      {{o.opinion}}
    </div>
   </q-item-label>
  </q-item-section>
  <q-item-section side v-if="o.result!='I'">
   <q-item-label caption>{{o.time}}</q-item-label>
   <q-icon :name="oIcons[o.result]" :color="o.result=='P'?'primary':'red'"></q-icon>
  </q-item-section>
 </q-item>
 <!-- 会签时，其他人的意见 -->
 <q-item v-for="o in s.olist">
  <q-item-section>
   <q-item-label>{{o.signer}}</q-item-label>
   <q-item-label caption>
    <div>{{o.opinion}}</div>
   </q-item-label>
  </q-item-section>
  <q-item-section side v-if="o.result!='I'">
     <q-item-label caption>{{o.time}}</q-item-label>
     <q-icon :name="oIcons[o.result]" :color="o.result=='P'?'primary':'red'"></q-icon>
  </q-item-section>
 </q-item>
</q-list>
</q-timeline-entry>
</q-timeline>
`
}