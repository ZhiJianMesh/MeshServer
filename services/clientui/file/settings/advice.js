export default {
inject:['service', 'tags'],
data(){return {}},
methods:{
copy(txt){
    this.service.copyToClipboard(txt).then(()=>{
        this.$q.notify("`" + txt + "`" + this.tags.copied);
    });
},
copyFmt(){
    this.service.copyToClipboard(this.tags.adviceFmt).then(()=>{
        this.$q.notify(this.tags.copied);
    });
}
},
template: `
<q-layout view="hHh lpr fFf">
  <q-header class="bg-grey-1 text-primary">
    <q-toolbar>
      <q-btn flat round icon="arrow_back" dense @click="service.go_back"></q-btn>
      <q-toolbar-title>{{tags.home.advice}}</q-toolbar-title>
    </q-toolbar>
  </q-header>
  <q-page-container>
    <q-page class="q-pa-md">    
<q-card>
 <q-card-section>
  <div class="text-h6">{{tags.sendAdvice}}</div>
  <div class="text-subtitle2">{{tags.fmtLike}}:
   <q-icon name="content_copy" color="primary" @click="copyFmt"></q-icon>
  </div>
 </q-card-section>
 <q-card-section style="white-space: pre-wrap;">{{tags.adviceFmt}}</q-card-section>
 <q-separator dark></q-separator>
 <q-card-section>
  <q-list>
   <q-item>
    <q-item-section avatar>
     <q-icon name="svguse:/assets/imgs/meshicons.svg#qq" color="secondary" size="2em"></q-icon>
    </q-item-section>
    <q-item-section @click="copy('3803810851')" class="cursor-pointer">3803810851</q-item-section>
   </q-item>
   <q-item>
    <q-item-section avatar><q-icon name="email" color="primary" size="2em"></q-icon></q-item-section>
    <q-item-section @click="copy('zhijianmesh@sina.com')" class="cursor-pointer">zhijianmesh@sina.com</q-item-section>
   </q-item>
  </q-list>
 </q-card-section>
</q-card>
<q-card class="q-mt-lg">
 <q-card-section>
    <div class="text-h6">{{tags.openSrcs}}</div>
 </q-card-section>
 <q-separator dark></q-separator>
 <q-card-section>
  <q-list>
  <q-item>
   <q-item-section>
    <a href='https://gitee.com/zhijian_net/enterprise' target='_blank' style='color:#00f;'>码云</a>
   </q-item-section>
   <q-item-section side>
    <q-icon name="content_copy" color="primary"
     @click="copy('https://gitee.com/zhijian_net/enterprise')"></q-icon>
   </q-item-section>
  </q-item>
  <q-item>
   <q-item-section>
    <a href='https://gitcode.net/zhijian_net/enterprise' target='_blank' style='color:#00f;'>CSDN</a>
   </q-item-section>
   <q-item-section side>
    <q-icon name="content_copy" color="primary"
     @click="copy('https://gitcode.net/zhijian_net/enterprise')"></q-icon>
   </q-item-section>
  </q-item>
  <q-item>
   <q-item-section>
    <a href='https://github.com/ZhiJianMesh/endterpris' target='_blank' style='color:#00f;'>Github</a>
   </q-item-section>
   <q-item-section side>
    <q-icon name="content_copy" color="primary"
     @click="copy('https://github.com/ZhiJianMesh/endterpris')"></q-icon>
   </q-item-section>
  </q-item>
  </q-list>
 </q-card-section>
</q-card>
</div>
    </q-page>
  </q-page-container>
</q-layout>
`
}