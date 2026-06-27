export default {
inject: ['service', 'tags'],
data() {return {
    name: this.$route.query.service,
    cid: this.$route.query.cid,
    app: {
        displayName: "",
        version: 0,
        sVer: '0.0.0',
        author: "",
        level: 100,
        icon: "",
        cloud:false //个人服务，都访问云侧
    },
    intro: {},
    subTitle: '',
    preImgNo:0,
    imgWidth:'90vw',
    isNormal:true,
    isInstalled:true,
    action: '',
    install:{percent:0,info:"",dlg:false}
}},
mounted() {
  window.installProgress = this.progress;//用于显示进度
},
created() {
    if(document.documentElement.clientWidth>document.documentElement.clientHeight) {
        this.imgWidth="30vw";
    }
    this.isNormal=!App.isBuiltin(this.name);
    this.app = this.service.list[this.name];
    request({method:"GET",url:"/api/introduce",private:false,cloud:this.app.cloud},this.name).then(resp=> {
        if (resp.code!=RetCode.OK) {
            this.intro = {descrs: [this.app.displayName], images:[]};
            return;
        }
        var intro=resp.data;
        if (intro.images && intro.images.length > 0) {
            var baseUrl='/'+this.name;
            for(var i in intro.images) {
                var src=intro.images[i].src;
                if(src.substring(0,1)=="/") {
                    intro.images[i].src=baseUrl+src;
                } else {
                    intro.images[i].src=baseUrl+'/'+src;
                }
            }
            this.subTitle = intro.images[0].info;
        }
        this.intro=intro;
    });

    this.refreshUI();
},
methods: {
scroll(event) {
    if(!this.intro||!this.intro.images) {
        return;
    }
    var n=this.intro.images.length;
    var i=parseInt(event.horizontalPercentage*n);
    if(i<n && this.preImgNo!=i) {
        this.subTitle = this.intro.images[i].info;
        this.preImgNo=i;
    }
},
appAction() {
    this.install.dlg=true;
    this.install.percent=0;
    this.install.info="";
    var jsCbId=__regsiterCallback(this.refreshUI)
    if(this.action==this.tags.install) {
        App.install(this.name, this.app.cloud, jsCbId);
    } else {
        App.update(this.name, jsCbId);
    }
    this.action=this.tags.waitting;
},
unInstall() {
    if(!this.isNormal) {
        return;
    }
    App.unInstall(this.name, __regsiterCallback(this.refreshUI));
    this.action=this.tags.waitting;
},
progress(inc,info){
    if(this.install.percent+inc>100) {
         this.install.percent=100;
     } else {
         this.install.percent+=inc;
     }
    this.install.info=info;
},
refreshUI(r) {
    if(r && r.code != RetCode.OK) {
        this.$refs.errDlg.showErr(r.code, r.info);
    }
    this.install={dlg:false,percent:0,info:""};
    this.isInstalled=App.isInstalled(this.name);
    if (this.isInstalled){
        request({method:"GET",url:"/api/client_info",private:false}, this.name).then(resp => {
            if (resp.code!=RetCode.OK) {
                Console.warn("Fail to get client info " + resp);
                return;
            }
            if(this.app.version<resp.data.version) {
                this.action=this.tags.update;
            } else {
                this.action="";
            }
        });
    } else {
        this.action = this.tags.install;
    }
}
},
template: `
<q-layout view="hHh lpr fFf">
  <q-header class="bg-grey-3">
   <q-list class="q-pa-sm">
    <q-item>
     <q-item-section avatar>
      <q-btn flat round icon="arrow_back" dense @click="service.go_back" class="text-primary"></q-btn>
     </q-avatar></q-item-section>
     <q-item-section avatar><q-avatar square>
      <img :src="app.icon">
     </q-avatar></q-item-section>
     <q-item-section>
      <q-item-label class="text-primary">{{app.displayName}}/{{app.service}}</q-item-label>
      <q-item-label caption>{{tags.author}} {{app.author}}</q-item-label>
      <q-item-label caption>{{tags.version}} {{app.sVer}}</q-item-label>
     </q-item-section>
    </q-item>
   </q-list>
  </q-header>
  <q-footer bordered class="bg-grey-2">
    <q-toolbar class="row justify-center">
      <q-btn rounded color="primary" :label="action" @click="appAction" v-show="action!=''"></q-btn>
      <q-btn outline rounded color="primary" :label="tags.unInstall" @click="unInstall" v-show="isNormal && isInstalled"></q-btn>
    </q-toolbar>
  </q-footer>
  
   <q-page-container>
     <q-page class="q-pa-none"> 
<q-list class="q-pa-md">
<q-item>
 <q-item-section>
  <q-item-label v-for="descr in intro.descrs" style="text-indent:2em;">{{descr}}</q-item-label>
  <q-item-label caption>{{subTitle}}</q-item-label>
 </q-item-section>
</q-item>

<q-item>
 <q-item-section>
  <q-scroll-area style="height:60vh;width:100%;" horizontal @scroll="scroll">
  <div class="row no-wrap">
   <div v-for="img in intro.images" class="q-pa-sm">
    <q-img :src="img.src" :style="{width:imgWidth}" @click="subTitle=img.info"></q-img>
   </div>
  </div>
  </q-scroll-area>
 </q-item-section>
</q-item>
</q-list>
    </q-page>
  </q-page-container>
</q-layout>

<q-dialog v-model="install.dlg" persistent>
 <q-card style="min-width:62vw" class="q-pa-lg">
  <q-linear-progress :value="install.percent/100" color="primary" size="xl"></q-linear-progress>
  <div>{{install.info}}</div>
 </q-card>
</q-dialog>
<component-alert-dialog :title="tags.failToCall" :errMsgs="tags.errMsgs" :close="tags.close" ref="errDlg"></component-alert-dialog>
`
}