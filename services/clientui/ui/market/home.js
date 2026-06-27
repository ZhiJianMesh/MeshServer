const APPS_TAB="apps_tab";
export default {
inject:['service', 'tags'],
data() {return {
    pData:{cur:1, max:0, list:[]},
    eData:{cur:1, max:0, list:[]},
    tab:storageGet(APPS_TAB, "enterprise")
}},
created(){
    this.queryApps(1);
},
methods:{
enterprise_apps(pg) {
    if(Companies.cid() <= 0) {
        this.apps=[];
        this.$refs.errDlg.show(this.tags.notLogin);
        return;
    }
    var offset=(parseInt(pg)-1) * this.service.N_PAGE;
    var opts={
        method:"GET",
        private:false,
        url:"/service/list?cid=" + Companies.cid() + "&offset=" + offset + "&num=" + this.service.N_PAGE
    };
    request(opts, "company").then(resp=>{
        if(resp.code != RetCode.OK) {
            this.eData.max=0;
            this.eData.cur=1;
            this.apps=[];
            Console.info("error, code:" + resp.code + ",info:" + resp.info)
            return;
        }
        this.format_apps(resp.data.total, resp.data.cols, resp.data.services, false, this.eData);
    })
},
personal_apps(pg) {
    request({method:"GET", url:"/getomauth", cloud:true}, "om").then(ar => {
        var level=1;
        if(ar.code==RetCode.OK) {
            for(var l of ar.data.list) {
                if(l.service=='om'||l.service=='*') {
                    level=0; //拥有om权限的才可以显示level为0的服务
                }
            }
        }
        var offset=(parseInt(pg)-1) * this.service.N_PAGE;
        var opts={
            method:"GET", private:false, cloud:true,
            url:"/service/personal?offset=" + offset
             + "&num=" + this.service.N_PAGE + "&level=" + level
        };
        request(opts, "appstore").then(resp=>{
            if(resp.code != RetCode.OK) {
                this.pData.max=0;
                this.pData.cur=1;
                Console.info("error, code:" + resp.code + ",info:" + resp.info)
                return;
            }
            this.format_apps(resp.data.total, resp.data.cols, resp.data.services, true, this.pData);
        })    
    })
},
detail(service) {
    this.$router.push('/detail?service='+service+"&cid="+Companies.cid())
},
format_apps(total, cols, data, cloud, ctrl) {
    ctrl.max=Math.ceil(total/this.service.N_PAGE);
    var apps=[];
    for(var d of data) {
        var o={};
        for(var i in cols) {
            o[cols[i]]=d[i];
        }
        var icon=App.serviceIcon(o.service);
        if(icon!="") {
            o['icon']=icon;
        } else {
            var iconUrl="/" + o.service + "/favicon.png";
            if(cloud) {//个人应用从云上获得，此参数需要端侧浏览器处理
                iconUrl += "?cloud=true";
            }
            o['icon'] = iconUrl;
        }
        o['cloud']=cloud;
        o.sVer=App.intToVer(parseInt(o.version));
        this.service.list[o.service]=o;
        apps.push(o);
    }
    ctrl.list=apps;
},
queryApps(pg) {
    storageSet(APPS_TAB, this.tab);
    if(this.tab=="enterprise") {
        this.enterprise_apps(pg);
    } else {
        this.personal_apps(pg);
    }
}
},

template: `
<q-layout view="hHh lpr fFf">
 <q-header elevated>
  <q-toolbar class="bg-grey-1 text-primary">
   <q-toolbar-title>{{tags.app_name}}</q-toolbar-title>
  </q-toolbar>
  <q-tabs v-model="tab" class="bg-grey-2 text-primary" @update:model-value="queryApps(1)" active-bg-color="green-1" dense>
   <q-tab name="enterprise" icon="svguse:/assets/imgs/meshicons.svg#company" :label="tags.enterprise"></q-tab>
   <q-tab name="personal" icon="person" :label="tags.personal"></q-tab>
  </q-tabs>
 </q-header>
<q-page-container><q-page class="q-pa-md">
 <q-tab-panels v-model="tab">
  <q-tab-panel name="enterprise">
   <div class="q-pa-sm flex flex-center" v-if="eData.max>1">
    <q-pagination v-model="eData.cur" color="primary" :max="eData.max" max-pages="10"
     boundary-numbers="false" @update:model-value="queryApps"></q-pagination>
   </div>
   <q-list separator>
    <q-item clickable v-ripple v-for="a in eData.list" @click="detail(a.service)">
     <q-item-section avatar><q-avatar square>
      <img :src="a.icon">
     </q-avatar></q-item-section>
     <q-item-section>
      <q-item-label>{{a.displayName}}/{{a.service}}</q-item-label>
      <q-item-label>{{a.author}}</q-item-label>
     </q-item-section>
    </q-item>
   </q-list>
  </q-tab-panel>
  <q-tab-panel name="personal">
   <div class="q-pa-sm flex flex-center" v-if="pData.max>1">
    <q-pagination v-model="pData.cur" color="primary" :max="pData.max" max-pages="10"
     boundary-numbers="false" @update:model-value="queryApps"></q-pagination>
   </div>
   <q-list separator>
    <q-item clickable v-ripple v-for="a in pData.list" @click="detail(a.service)">
     <q-item-section avatar><q-avatar square>
      <img :src="a.icon">
     </q-avatar></q-item-section>
     <q-item-section>
      <q-item-label>{{a.displayName}}/{{a.service}}</q-item-label>
      <q-item-label>{{a.author}}</q-item-label>
     </q-item-section>
    </q-item>
   </q-list>
  </q-tab-panel>
 </q-tab-panels>
</q-page></q-page-container>
</q-layout>
<component-alert-dialog :title="tags.failToCall" :errMsgs="tags.errMsgs" :close="tags.close" ref="errDlg"></component-alert-dialog>
`}