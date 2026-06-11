import AlertDialog from "./alert_dialog.js"
//一边输入地址，一边过滤地址的组件
export default {
data() {return {
    loginDlg:false,
    hidePwd:true,
    loginAcc:'',
    loginPwd:'',
    callback:null,
    logining:false
}},
props: {
    accType:{type:String,required:true, default:"N"},
    label:{type:String,required:false, default:"登录"},
    account:{type:String,required:false, default:"帐号"},
    pwd:{type:String,required:false, default:"密码"},
    failToCall:{type:String,required:false, default:"调用失败"},
    cancel:{type:String,required:false, default:"取消"}
},
components:{
    "alert-dialog":AlertDialog
},
methods:{
actLogin() {
    this.logining = true;
    var jsCbId=__regsiterCallback(resp => {
        this.logining = false;
        if(resp.code!=RetCode.OK) {
            this.$refs.errMsg.showErr(resp.code, resp.info);
            return;
        }
        this.loginAcc='';
        this.loginPwd='';
        this.loginDlg=false;
        if(this.callback && typeof(this.callback)=='function'){
            this.callback(resp);
        }
        this.loginDlg=false;
    });
    Companies.login(this.loginAcc, this.loginPwd, this.accType, jsCbId);
},
show(cb) {
    this.loginDlg=true;
    this.callback=cb;
}
},
template: `
<q-dialog v-model="loginDlg">
  <q-card style="min-width:62vw;max-width:80vw">
    <q-card-section>
      <div class="text-h6">{{label}}</div>
    </q-card-section>
    <q-separator></q-separator>
    <q-card-section class="q-pt-none">
      <q-input v-model="loginAcc" autofocus :label="account"></q-input>
      <q-input v-model="loginPwd" autofocus :type="hidePwd?'password':'text'" :label="pwd">
        <template v-slot:append>
          <q-icon :name="hidePwd ? 'visibility_off':'visibility'"
            class="cursor-pointer" @click="hidePwd=!hidePwd"></q-icon>
        </template>
      </q-input>
    </q-card-section>
    <q-card-actions align="right">
      <q-btn color="primary" flat :label="cancel" v-close-popup :disable="logining"></q-btn>
      <q-btn color="primary" :label="label" @click="actLogin" :disable="logining"></q-btn>
    </q-card-actions>
    <q-linear-progress indeterminate rounded color="pink"
    class="q-mt-sm" v-show="logining"></q-linear-progress>
  </q-card>
</q-dialog>
<alert-dialog :title="failToCall" ref="errMsg"></alert-dialog>
`
}