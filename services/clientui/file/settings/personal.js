export default {
inject:['service', 'tags'],
data(){return {
    chgPwdDlg:false,
    oldPwd:'',
    newPwd:'',
    cfmPwd:'',
    changed:false,
    hidePwd:{o:true,n:true,c:true},
    userInfo:{}
}},
created() {
    this.get_userbase();
},
methods:{
get_userbase() {
    this.service.getUserInfo().then(ui=>{
        this.userInfo=ui;
        var dt = new Date();
        dt.setTime(ui.birthday?ui.birthday*86400000:0)
        this.userInfo['sBirthday']=dt.toLocaleDateString();
    });
},
changePwd() {
    if(this.oldPwd==''||this.newPwd==''||this.newPwd!=this.cfmPwd){
        this.$refs.errDlg.show(this.tags.invalidPwd);
        return;
    }
    var company=this.service.curCompany();
    var req={oldPassword:this.oldPwd,newPassword:this.newPwd,confirmPassword:this.cfmPwd};
    request({method:"POST", url:"/api/changePassword", data:req, cloud:company.cloud}, company.userService).then(resp => {
        if(resp.code != RetCode.OK) {
            this.$refs.errDlg.showErr(resp.code, resp.info);
            return;
        }
        this.chgPwdDlg=false
    });
},
saveChanges(){
    var company=this.service.curCompany();
    var req = {
        nickName:this.userInfo.nickName,
        mobile:this.userInfo.mobile,
        email:this.userInfo.email,
        birthday:this.userInfo.birthday,
        sex:this.userInfo.sex
    };
    request({method:"POST", url:"/api/setBaseInfo", data:req, cloud:company.cloud}, company.userService).then(resp => {
        if(resp.code != RetCode.OK) {
            this.$refs.errDlg.showErr(resp.code, resp.info);
            return;
        }
        this.changed=false;
    });
},
birthChged(v){
    var dt = Date.parse(v);
    this.userInfo.birthday=Math.ceil(dt/86400000);
    this.changed=true;
}
},

template: `
<q-layout view="hHh lpr fFf">
 <q-header class="bg-grey-1 text-primary">
  <q-toolbar>
   <q-btn flat round icon="arrow_back" dense @click="service.go_back"></q-btn>
   <q-toolbar-title>{{tags.home.personal}} - {{userInfo.account}}</q-toolbar-title>
  </q-toolbar>
 </q-header>
  <q-page-container>
   <q-page class="q-pa-md">
<q-markup-table bordered="false" flat>
 <tr>
  <td>{{tags.nickName}}</td>
  <td>
   <q-input v-model="userInfo.nickName" dense @update:model-value="changed=true"></q-input>
  </td>
 </tr>
 <tr>
  <td>{{tags.user.sex}}</td>
  <td>
   <q-option-group v-model="userInfo.sex" :options="tags.user.sexOpts" color="primary" inline
    @update:model-value="changed=true"></q-option-group>
  </td>
 </tr>
 <tr>
  <td>{{tags.user.birthday}}</td>
  <td>
   <component-date-input v-model="userInfo.sBirthday" max="today" min="1900/1/1"
   @update:modelValue="birthChged"></component-date-input>
  </td>
 </tr>
 <tr>
  <td>{{tags.email}}</td>
  <td>
    <q-input v-model="userInfo.email" :rules="[v=>/.+@.+/.test(v) || tags.invalidEmail]" dense
    @update:model-value="changed=true"></q-input>
  </td>
 </tr>
 <tr>
  <td>{{tags.mobile}}</td>
  <td>
    <q-input v-model="userInfo.mobile" maxlength=11 :rules="[v=>/1[0-9]{10}/.test(v) || tags.invalidMobile]" dense
    @update:model-value="changed=true"></q-input>
  </td>
 </tr>
 <tr clickable v-ripple @click="chgPwdDlg=true">
  <td>{{tags.chgPwd}}</td>
  <td><q-icon name="chevron_right" class="text-primary"></q-icon></td>
 </tr>
</q-markup-table>
<div align="center" v-show="changed">
  <q-btn icon="save" rounded @click="saveChanges" color="primary" :label="tags.save"></q-btn>
</div>
    </q-page>
  </q-page-container>
</q-layout>

<component-alert-dialog :title="tags.failToCall" :errMsgs="tags.errMsgs"
 :close="tags.close" ref="errDlg"></component-alert-dialog>
 
<!-- change password dialog -->
<q-dialog v-model="chgPwdDlg">
 <q-card style="min-width:62vw;max-width:80vw">
  <q-card-section>
    <div class="text-h6">{{tags.chgPwd}}-{{account}}</div>
  </q-card-section>
  <q-card-section class="q-pt-none">
   <q-input dense v-model="oldPwd" autofocus :type="hidePwd.o?'password':'text'"
    :label="tags.oldPwd" :rules="[val => !!val || tags.oldPwd + '' + tags.cantbeNull]">
      <template v-slot:append>
       <q-icon :name="hidePwd.o ? 'visibility_off':'visibility'"
         class="cursor-pointer" @click="hidePwd.o=!hidePwd.o"></q-icon>
     </template>
   </q-input>
   <q-input dense v-model="newPwd" autofocus :type="hidePwd.n?'password':'text'"
    :label="tags.newPwd" :rules="[val => !!val || tags.newPwd + '' + tags.cantbeNull]">
      <template v-slot:append>
       <q-icon :name="hidePwd.n ? 'visibility_off':'visibility'"
         class="cursor-pointer" @click="hidePwd.n=!hidePwd.n"></q-icon>
     </template>
   </q-input>
   <q-input dense v-model="cfmPwd" autofocus :type="hidePwd.c?'password':'text'"
    :label="tags.cfmPwd" :rules="[
     val => !!val || tags.cfmPwd + ' ' + tags.cantbeNull,
     val => val==newPwd || tags.cfmPwd + ',' + tags.newPwd + ' ' + tags.mustbeEqual
     ]">
     <template v-slot:append>
       <q-icon :name="hidePwd.c ? 'visibility_off':'visibility'"
        class="cursor-pointer" @click="hidePwd.c=!hidePwd.c"></q-icon>
     </template>
   </q-input>
  </q-card-section>
  <q-card-actions align="right">
   <q-btn color="primary" :label="tags.ok" @click="changePwd"></q-btn>
   <q-btn color="primary" flat :label="tags.cancel" v-close-popup></q-btn>
  </q-card-actions>
 </q-card>
</q-dialog>
`
}