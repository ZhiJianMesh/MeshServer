export default {
inject:['service', 'tags'],
data(){return {
    cid:'',
    companyName:'',
    runMode:'SINGLETON',
    accessCode:'',
    insideAddr:'',
    outsideAddr:'',
    changed:false,
    saveAt:0,
    logo:''
}},
created() {
    this.init();
},
methods:{
init() {
    var c=this.service.curCompany();
    this.cid=c.id;
    this.companyName=c.name;
    this.insideAddr=c.insideAddr;
    this.outsideAddr=c.outsideAddr;
    this.accessCode=c.accessCode;
    this.runMode=c.runMode;
    this.service.mode=c.runMode;
    Companies.getLogo(c.id, __regsiterCallback(png=>{
        if(png) {
            this.logo="img:"+png;
        } else {
            this.logo="/assets/imgs/logo_example.png";
        }
    }));
},
save() {
    var cur = new Date().getTime();
    if(this.saveAt>0) {
        if(cur-this.saveAt<10000) {
            return; //避免快速的重复点击
        }
        this.saveAt=0;
    }
    this.saveAt=cur;

    Companies.add(this.cid, this.accessCode, this.insideAddr, __regsiterCallback(resp => {
        this.saveAt=0;
        if(resp.code!=RetCode.OK) {
            this.$refs.alertDlg.showErr(resp.code, resp.info);
            return;
        }
        var c=this.service.curCompany();
        this.companyName=c.name;
        this.insideAddr=c.insideAddr;
        this.outsideAddr=c.outsideAddr;
        this.accessCode=c.accessCode;
        this.changed=false;
        this.$refs.alertDlg.show(this.tags.successToConnect);
    }));
},
copy() {
    var txt=this.cid+"\n"
        +this.companyName+"\n"
        +this.accessCode+"\n"
        +this.insideAddr+"\n"
        +this.outsideAddr+"\n";
    this.service.copyToClipboard(txt).then(()=>{
        this.$q.notify(this.tags.copied);
    });
},
refresh() {
    Companies.refreshEntrance(__regsiterCallback(resp => {
        if(resp.code!=RetCode.OK) {
            this.$refs.alertDlg.showErr(resp.code, resp.info);
            return;
        }
        this.init();
    }));
}
},
template: `
<q-layout view="hHh lpr fFf">
  <q-header class="bg-grey-1 text-primary">
    <q-toolbar>
      <q-btn flat icon="arrow_back" dense @click="service.go_back"></q-btn>
      <q-toolbar-title>{{tags.home.company}}</q-toolbar-title>
    </q-toolbar>
  </q-header>
  <q-page-container>
    <q-page class="q-pa-md">
<q-card>
 <q-card-section>
  <q-list><q-item>
    <q-item-section avatar>
      <q-avatar size="3em"><q-icon :name="logo" size="2em"></q-icon></q-avatar>
    </q-item-section>
    <q-item-section class="text-h6">
      <q-item-label>{{companyName}}</q-item-label>
      <q-item-label caption>{{tags.cfg.id}}:{{cid}}</q-item-label>
    </q-item-section>
    <q-item-section side v-show="changed">
      <q-btn icon="save" @click="save" color="primary" :loading="saveAt>0" rounded flat></q-btn>
    </q-item-section>
    <q-item-section side>
      <q-icon name="content_copy" @click="copy" color="secondary"></q-icon>
    </q-item-section>
       <q-item-section side>
      <q-icon name="refresh" @click="refresh" color="primary"></q-icon>
    </q-item-section>
  </q-item></q-list>
 </q-card-section>
</q-card>
<q-markup-table bordered="false" flat class="q-pa-md q-mt-lg">
  <tr>
   <td>{{tags.cfg.accessCode}}</td>
   <td><q-input v-model="accessCode" dense @update:model-value="changed=true"></q-input></td>
  </tr>
  <tr>
   <td>{{tags.insideAddr}}</td>
   <td><q-input v-model="insideAddr" dense label-slot @update:model-value="changed=true"></q-input></td>
  </tr>
  <tr>
   <td>{{tags.outsideAddr}}</td>
   <td><q-input v-model="outsideAddr" dense label-slot @update:model-value="changed=true"></q-input></td>
  </tr>
</q-markup-table>
    </q-page>
  </q-page-container>
</q-layout>

<component-alert-dialog :title="tags.alert" :errMsgs="tags.errMsgs"
 :close="tags.close" ref="alertDlg"></component-alert-dialog>
`}