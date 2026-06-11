//告警窗口组件,component-alert-dialog
export default {
data() {return {
    dlg:false,
    iTitle:'',
    message:''
}},
props: {
    errMsgs:{type:Object,default:{}},
    title:{type:String,default:"注意"}
},
created(){
    this.iTitle=this.title;
},
methods:{
show(msg,title) {
    this.message=msg;
    if(title) this.iTitle=title;
    this.dlg=true;
},
showErr(code,info,ext) {
    if(ext) {
        if((typeof ext)==='string'||(ext instanceof String)) {
            this.iTitle=ext;
            this.message=formatErr(code,info,this.errMsgs); //必须提前加载errors.xx.js
        } else {
            this.message=formatErr(code,info,ext);
        }
    } else {
        this.message=formatErr(code,info,this.errMsgs); //必须提前加载errors.xx.js
    }
    this.dlg=true;
}
},
template: `
<q-dialog v-model="dlg" @hide="iTitle=title">
  <q-card style="min-width:60vw;">
    <q-card-section class="row items-center q-pb-none">
     <div class="text-h6">{{iTitle}}</div>
     <q-space></q-space>
     <q-btn icon="close" flat dense v-close-popup></q-btn>
    </q-card-section>
    <q-separator></q-separator>
    <q-card-section class="q-pt-md" v-html="message"></q-card-section>
  </q-card>
</q-dialog>
`
}