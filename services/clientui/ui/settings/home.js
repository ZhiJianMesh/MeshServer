export default {
inject:['service', 'tags'],
data(){return{
    authorized:false,
    account:'',
    nickName:'',
    companyName:'',
    companies:[],
    curCompanyId:-1,
    regComDta:{creditCode:'',name:'',pwd:'',cfmPwd:'',verifyCode:'',session:'',
      addr:{province:'',city:'',county:''},vcImg:'',pwdVis:false}, //注册新公司数据
    addComDta:{id:'',accessCode:'',insideAddr:'',needInside:false}, //添加已存在公司数据
    newUsrDta:{account:'',pwd:'',confirmPwd:'',verifyCode:'',session:'',pwdVis:false,vc:''}, //注册个人帐号
    dlg:{com:false,comTab:'checkin',usr:false,doing:false},//是否正在添加公司，用于显示进度条
    rootComId:1
}},
created() {
    this.rootComId=Companies.rootCompanyId();
    this.init();
},
methods:{
init() {
    var company=this.service.curCompany();
    this.newComData={id:'',accessCode:'',insideAddr:'',needInside:false};
    Companies.list(__regsiterCallback(resp=>{
        this.companies=resp.data.list;
        //必须用let，否则在循环中使用异步闭包会用最后一个
        //安卓7部分实现let
        for(let c of this.companies) {
            Companies.getLogo(c.id, __regsiterCallback(png=>{
                if(png) {
                    c['logo']="img:"+png;
                } else {
                    c['logo']="/assets/imgs/logo_example.png";
                }
            }))
        }
    }));

    this.account=this.tags.account;
    this.nickName=this.tags.nickName;
    if(company.authorized) {
        this.authorized=true;
        this.service.getUserInfo().then(userInfo=>{
            this.account=userInfo.account;
            this.nickName=userInfo.nickName;
        });
    } else {
        this.authorized=false;
    }
    this.companyName=company.name;
    this.curCompanyId=company.id;
},
about() {
    App.openApp("clientui");
},
jump(pg) {
    this.$router.push(pg)
},
logInOrOut() {
    if(this.service.curCompany().authorized) {
        Companies.logout(__regsiterCallback(resp => {
            this.account=this.tags.account;
            this.nickName=this.tags.nickName;
            this.authorized=false;
            this.service.clrUserInfo();
        }));
    } else {
        this.$refs.loginDlg.show(resp => {
            this.init()
        });
    }
},
company_btn_act() {
    if(this.dlg.comTab=='checkin') {
        this.addCompany()
    } else {//register
        this.regCompany();
    }
},
addCompany(){
    var dta=this.addComDta;
    this.dlg.doing=true;
    Companies.add(dta.id, dta.accessCode, dta.insideAddr,
    __regsiterCallback(resp=>{
        this.dlg.doing=false;
        if(resp.code==RetCode.OK) {
            this.dlg.com=false;
            this.init();
            return;
        }
        if(!dta.insideAddr) { //端侧无法外网访问，httpdns无法探测到，则直接用内网IP探测
            dta.needInside=true;
        } else {
            this.$refs.errDlg.showErr(resp.code, resp.info);
        }
    }))
},
regCompany() {
    var jsCbId=__regsiterCallback(resp=>{
        this.dlg.doing=false;
        if(resp.code!=RetCode.OK) {
            this.$refs.errDlg.showErr(resp.code, resp.info);
            return;
        }
        this.dlg.com=false;
        this.$refs.successDlg.show(this.tags.cfg.sucessToReg+resp.data.cid);
    });
    var d=this.regComDta;
    if(!JStr.chkCreditCode(d.creditCode)) {
        __default_jscb(jsCbId, {code:RetCode.WRONG_PARAMETER, info:this.tags.cfg.invalidCredit});
        return;
    }
    if(!Secure.isPwdStrong(d.creditCode,d.pwd,6,2,3)) {
        __default_jscb(jsCbId, {code:RetCode.WRONG_PARAMETER, info:this.tags.cfg.invalidPwd});
        return;
    }
    if(d.pwd != d.cfmPwd) {
        __default_jscb(jsCbId, {code:RetCode.WRONG_PARAMETER, info:this.tags.cfg.invalidCfmPwd});
        return;
    }
    this.dlg.doing=true;
    var data={creditCode:d.creditCode,
        pwd:Secure.sha256(d.pwd),
        cfmPwd:Secure.sha256(d.cfmPwd),
        verifyCode:d.verifyCode,
        session:d.session,
        name:d.name,
        partition:250000, //私有云统一的分区ID
        info:'',
        country:'86',
        province:d.addr.province,
        city:d.addr.city,
        county:d.addr.county,
        runMode:"ROOT"
    };
    var opts={url:"/company/register", method:"POST", data:data, private:false, cloud:true}
    request(opts, "company").then(resp=>{
        __default_jscb(jsCbId, resp)
    })
},
setCurCompany(cid) {
    this.curCompanyId=cid;
    Companies.setCur(cid, __regsiterCallback(resp => {
        if(resp.code!=RetCode.OK) {
            this.$refs.errDlg.showErr(resp.code, resp.info);
        } //即使失败，也要切换过去
        this.init();//setCur需要重新probe，所以必须等它完成才能init
    }));
},
exitCompany(){
    if(Companies.remove(this.curCompanyId)) {
        this.init();
    }
},
showRegister() {
    this.dlg.usr=true;
    this.refreshUsrVc();
},
registerAcc() { //注册个人账号
    if(this.newUsrDta.pwd=='' || this.newUsrDta.pwd!=this.newUsrDta.confirmPwd) {
        this.$refs.errDlg.show(this.tags.invalidPwd);
        return;        
    }
    this.dlg.doing=true;
    var opts={method:"POST",url:"/user/register", private:false, data:{
        account:this.newUsrDta.account,
        accType:'N',
        password:this.newUsrDta.pwd,
        confirmPassword:this.newUsrDta.confirmPwd,
        session:this.newUsrDta.session,
        verifyCode:this.newUsrDta.verifyCode
    },cloud:true};
    request(opts, SERVICE_UNIUSER).then(resp=>{
        this.dlg.doing=false;
        if(resp.code!=RetCode.OK) {
            this.$refs.errDlg.showErr(resp.code, resp.info);
            return;
        }
        this.dlg.usr=false;
        this.newUsrDta={account:'',pwd:'',confirmPwd:'',verifyCode:'',session:'',pwdVis:false,vc:''};
    })
},
refreshUsrVc(){
    var url="/image?w=120&h=40";
    request({method:"GET",url:url,private:false,cloud:true},"verifycode").then(resp=>{
        if(resp.code==RetCode.OK) {
            this.newUsrDta.vc=resp.data.img;
            this.newUsrDta.session=resp.data.session;
        }
    })
},
refreshRegVc(){
    var url="/image?w=130&h=40&l=5";
    request({method:"GET",url:url,private:false,cloud:true},"verifycode").then(resp=>{
        if(resp.code==RetCode.OK) {
            this.regComDta.vcImg=resp.data.img;
            this.regComDta.session=resp.data.session;
        }
    })
},
newComTabChged(v) {
    if(v=='register'){
        this.refreshRegVc();
    }
},
chkCredit(code) {
    return JStr.chkCreditCode(code);
}
},
template: `
<q-layout view="hHh lpr fFf">
<q-header class="bg-grey-1">
 <q-list class="q-pa-sm" dense><q-item>
  <q-item-section top thumbnail class="q-ml-none">
   <q-btn-dropdown color="primary" icon="group" push no-caps no-wrap fab-mini flat dense>
    <q-list dense>
      <q-item v-for="c in companies" clickable v-close-popup @click="setCurCompany(c.id)">
       <q-item-section avatar>
        <q-avatar square :icon="c.logo" :color="c.id==curCompanyId?'primary':'green-1'" text-color="white"></q-avatar>
       </q-item-section>
       <q-item-section no-wrap>{{c.name}}</q-item-section>
      </q-item>
      <q-item clickable v-close-popup @click="dlg.com=true">
       <q-item-section avatar>
        <q-avatar icon="add" color="teal-3" text-color="white"></q-avatar>
       </q-item-section>
       <q-item-section no-wrap>{{tags.addCompany}}</q-item-section>
      </q-item>
    </q-list>
   </q-btn-dropdown>
  </q-item-section>
  <q-item-section>
   <q-item-label caption>{{account}}/{{nickName}}</q-item-label>
   <q-item-label caption>{{companyName}}</q-item-label>
  </q-item-section>
  <q-item-section side top>
   <div class="row no-wrap">
    <q-btn flat color="indigo" :label="tags.home.register"
     @click="showRegister" v-show="rootComId==curCompanyId&&!authorized"></q-btn>
    <q-btn flat dense color="primary" :label="authorized?tags.logout:tags.login" @click="logInOrOut"
     :icon-right="authorized?'logout':'login'"></q-btn>
   </div>
  </q-item-section>
 </q-item></q-list>
</q-header>
<q-page-container>
 <q-page class="q-pa-md">
<q-list class="q-pa-md">
  <q-item clickable v-ripple @click="jump('/company')" v-show="rootComId!=curCompanyId">
    <q-item-section avatar>
     <q-icon color="primary" name="business"></q-icon>
    </q-item-section>
    <q-item-section>{{tags.home.company}}</q-item-section>
    <q-item-section avatar>
     <q-icon name="chevron_right" class="text-primary"></q-icon>
    </q-item-section>
  </q-item>

  <q-item clickable v-ripple @click="jump('/personal')" v-show="authorized">
    <q-item-section avatar>
     <q-icon color="primary" name="person_pin_circle"></q-icon>
    </q-item-section>
    <q-item-section>{{tags.home.personal}}</q-item-section>
    <q-item-section avatar>
     <q-icon name="chevron_right" class="text-primary"></q-icon>
    </q-item-section>
  </q-item>

  <q-item clickable v-ripple @click="jump('/advice')">
    <q-item-section avatar>
     <q-icon color="brown" name="mail_outline"></q-icon>
    </q-item-section>
    <q-item-section>{{tags.home.advice}}</q-item-section>
    <q-item-section avatar>
     <q-icon name="chevron_right" class="text-primary"></q-icon>
    </q-item-section>
  </q-item>

  <q-item clickable v-ripple @click="about">
    <q-item-section avatar>
      <q-icon color="primary" name="error"></q-icon>
    </q-item-section>

    <q-item-section>{{tags.home.about}}</q-item-section>
    <q-item-section avatar>
      <q-icon name="open_in_new" class="text-primary"></q-icon>
    </q-item-section>
  </q-item>
  
  <q-item clickable v-ripple v-show="rootComId!=curCompanyId" @click="exitCompany">
    <q-item-section avatar>
      <q-icon color="red" name="cancel"></q-icon>
    </q-item-section>
    <q-item-section class="text-red">{{tags.home.exitCompany}}</q-item-section>
    <q-item-section avatar>
    </q-item-section>
  </q-item>
</q-list>
 </q-page>
</q-page-container>
</q-layout>

<q-dialog v-model="dlg.com"> <!-- 添加公司或注册新公司 -->
 <q-card style="min-width:62vw;max-width:80vw">
  <q-card-section>
   <div class="text-h6">{{tags.addCompany}}</div>
  </q-card-section>
  <q-card-section class="q-pt-none">
   <q-tabs v-model="dlg.comTab" dense class="text-grey" active-color="primary"
    indicator-color="primary" align="justify" narrow-indicator
    @update:model-value="newComTabChged">
    <q-tab name="checkin" :label="tags.checkin"></q-tab>
    <q-tab name="register" :label="tags.register"></q-tab>
   </q-tabs>
   <q-separator></q-separator>
   <q-tab-panels v-model="dlg.comTab" animated>
    <q-tab-panel name="checkin">
     <q-input v-model="addComDta.id" autofocus :label="tags.cfg.id"></q-input>
     <q-input v-model="addComDta.accessCode" :label="tags.cfg.accessCode"></q-input>
     <q-input v-model="addComDta.insideAddr" :label="tags.insideAddr" v-show="addComDta.needInside"></q-input>
    </q-tab-panel>
    <q-tab-panel name="register">
     <q-input autofocus v-model="regComDta.creditCode" :label="tags.cfg.creditCode" maxlength=18
     :rules="[v=>chkCredit(v)||tags.creditCodePls]"></q-input>
     <q-input v-model="regComDta.name" :label="tags.cfg.name" maxlength=50></q-input>
     <q-input v-model="regComDta.pwd" :label="tags.pwd" maxlength=20 :type="regComDta.pwdVis ? 'text':'password'">
      <template v-slot:append>
       <q-icon :name="regComDta.pwdVis ? 'visibility':'visibility_off'"
        class="cursor-pointer" @click="regComDta.pwdVis=!regComDta.pwdVis"></q-icon>
      </template>
     </q-input>
     <q-input v-model="regComDta.cfmPwd" :label="tags.cfmPwd" maxlength=20
      :type="regComDta.pwdVis ? 'text':'password'" :rules="[v=>v==regComDta.pwd||tags.invalidCfmPwd]">
      <template v-slot:append>
       <q-icon :name="regComDta.pwdVis ? 'visibility':'visibility_off'"
        class="cursor-pointer" @click="regComDta.pwdVis=!regComDta.pwdVis"></q-icon>
      </template>
     </q-input>
     <q-input v-model="regComDta.verifyCode" :label="tags.verifyCode">
       <template v-slot:append><img :src="regComDta.vcImg" @click="refreshRegVc"></template>
     </q-input>
     <address-input :label="tags.cfg.address" v-model="regComDta.addr"></address-input>
    </q-tab-panel>
   </q-tab-panels>
  </q-card-section>
  <q-card-actions align="right">
   <q-btn color="primary" :label="tags.ok" @click="company_btn_act" :disable="dlg.doing"></q-btn>
   <q-btn color="primary" flat :label="tags.cancel" v-close-popup :disable="dlg.doing"></q-btn>
  </q-card-actions>
  <q-linear-progress indeterminate rounded color="pink" class="q-mt-sm" v-show="dlg.doing"></q-linear-progress>
 </q-card>
</q-dialog>

<q-dialog v-model="dlg.usr"> <!-- 注册个人帐号 -->
 <q-card style="min-width:62vw;max-width:80vw">
  <q-card-section>
   <div class="text-h6">{{tags.home.registerAcc}}</div>
  </q-card-section>
  <q-card-section class="q-pt-none">
   <q-input v-model="newUsrDta.account" autofocus :label="tags.account"></q-input>
   <q-input v-model="newUsrDta.pwd" autofocus :type="newUsrDta.pwdVis?'text':'password'" :label="tags.pwd">
    <template v-slot:append>
     <q-icon :name="newUsrDta.pwdVis ? 'visibility' : 'visibility_off'"
      class="cursor-pointer" @click="newUsrDta.pwdVis=!newUsrDta.pwdVis"></q-icon>
    </template>
   </q-input>
   <q-input v-model="newUsrDta.confirmPwd" autofocus :type="newUsrDta.pwdVis?'text':'password'" :label="tags.cfmPwd">
    <template v-slot:append>
     <q-icon :name="newUsrDta.pwdVis ? 'visibility':'visibility_off'"
      class="cursor-pointer" @click="newUsrDta.pwdVis=!newUsrDta.pwdVis"></q-icon>
    </template>
   </q-input>
   <q-input v-model="newUsrDta.verifyCode" autofocus :label="tags.verifyCode">
    <template v-slot:append><img :src="newUsrDta.vc" @click="refreshUsrVc"></template>
   </q-input>
  </q-card-section>
  <q-card-actions align="right">
   <q-btn color="primary" :label="tags.ok" @click="registerAcc" :disable="dlg.doing"></q-btn>
   <q-btn color="primary" flat :label="tags.cancel" v-close-popup :disable="dlg.doing"></q-btn>
  </q-card-actions>
  <q-linear-progress indeterminate rounded color="pink"
  class="q-mt-sm" v-show="dlg.doing"></q-linear-progress>
 </q-card>
</q-dialog>

<component-alert-dialog :title="tags.failToCall" :errMsgs="tags.errMsgs"
 :close="tags.close" ref="errDlg"></component-alert-dialog>
<component-alert-dialog :title="tags.successToCall" :errMsgs="tags.errMsgs"
 :close="tags.close" ref="successDlg"></component-alert-dialog>
<component-login-dialog ref="loginDlg" :title="tags.login" :login="tags.login" 
 :cancel="tags.cancel" :close="tags.close" :failToCall="tags.failToCall"
 :account="tags.account" :pwd="tags.pwd" :accType="N">
</component-login-dialog>
`}