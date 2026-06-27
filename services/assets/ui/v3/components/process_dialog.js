export default {
data() {return {
    dlg:false,
    info:'',
    title:'',
    icon:'',
    state:0, //0:idle,1:doing,2:done
    action:null,
    actionDone:null
}},
props: {
    width:{type:String,required:false,default:"80vw"},
    ok:{type:String,required:false,default:"执行"},
    close:{type:String,required:false,default:"关闭"}
},
methods:{
show(title, info, icon, action, actionDone) {
    this.state=0;
    this.title=title;
    this.info=info;
    this.action=action;
    this.icon=icon;
    this.actionDone=actionDone;
    this.dlg=true;
},
setInfo(info) {
    this.info=info;
},
doAction() {
    this.state=1;
    if(!this.action||typeof(this.action)!='function'){
        return;
    }
    this.action(this).then(resp=> {
        this.state=2;
        if(this.actionDone&&typeof(this.actionDone)=='function') {
            this.actionDone(this, resp);
        }
    });
}
},
template: `
<q-dialog v-model="dlg" persistent>
<q-card :style="{width:width}" class="q-pa-lg">
   <q-card-section>
     <div class="text-h6">{{title}}</div>
   </q-card-section>
   <q-card-section>
     <div v-show="info" v-html="info"></div>
     <div class="text-center" v-show="state==1">
      <q-circular-progress indeterminate size="7em" show-value
      :thickness="0.2" color="lime" center-color="grey-8"
       track-color="transparent" class="text-white q-ma-md" dense>
       <q-icon :name="icon" size="2em" color="white"></q-icon>
      </q-circular-progress>
     </div>
    </q-card-section>
    <q-separator></q-separator>
    <q-card-actions align="right">
      <q-btn flat :label="close" color="primary" v-close-popup :disable="state==1"></q-btn>
      <q-btn :label="ok" color="primary" @click="doAction" v-show="state==0"></q-btn>
    </q-card-actions>
</q-card>
</q-dialog>
`
}


