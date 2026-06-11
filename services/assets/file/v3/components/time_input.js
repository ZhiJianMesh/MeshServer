import ScrollSelect from "./scroll_select.js"
//时间选择组件
export default{
components:{
    "scroll-select":ScrollSelect
},
data(){return {
    val:{h:0,m:0,s:0,needEmit:false},
    style:{cursor:'pointer',width:'12em'},
    hours:Array.from({length:24}, (_, i) => i>9?i:'0'+i),
    ms:Array.from({length:60}, (_, i) => i>9?i:'0'+i) //分&秒
}},
props: {
    modelValue:{type:Object},
    showSecond:{type:Boolean, default:true},
    disable:{type:Boolean, default:false}
},
emits: ['update:modelValue'],
watch:{
    modelValue(nv,_ov) {
        if(!nv || !nv.value || nv.value!=this.value) {
            this.val=this.parse(nv);
        }
    }
},
created(){
    if(!this.showSecond){
        this.style.width='9em';
    }
    this.val=this.parse(this.modelValue);
    if(this.val.needEmit) {
        var v=this.val;
        var dtl={hour:parseInt(v.h), minute:parseInt(v.m), second:parseInt(v.s)};
        this.$emit('update:modelValue',{value:this.value, details:dtl});
    }
},
methods:{
parse(t) {
    if(!t) {
        var dt=new Date();
        var v=dt.getHours();
        var hour=v<9?('0'+v):v;
        v=dt.getMinutes();
        var minute=v<9?('0'+v):v;
        v=dt.getSeconds();
        var second=v<9?('0'+v):v;
        return {h:hour, m:minute, s:second, needEmit:true};
    }

    if((typeof t) === 'string') {
        var ss=t.split(':');
        var v=parseInt(ss[0]);
        var hour=v<9?('0'+v):v;
        v=parseInt(ss[1]);
        var minute=v<9?('0'+v):v;
        var second='00';
        if(ss.length>2) {
            v=parseInt(ss[2]);
            second=v<9?('0'+v):v;
        }
        return {h:hour, m:minute, s:second, needEmit:true};
    }
    var d=t.details;
    return {h:d.hour>9?d.hour:('0'+d.hour),
         m:d.minute>9?d.minute:('0'+d.minute),
         s:d.second>9?d.second:('0'+d.second)};
},
confirm() {
    var v=this.val;
    var dtl={hour:parseInt(v.h), minute:parseInt(v.m), second:parseInt(v.s)};
    this.$emit('update:modelValue',{value:this.value, details:dtl});
    this.$refs._tm_ipt_dlg.hide();
}
},
computed: {
   value: {
      get() {
        var v=this.val;
        var i=parseInt(v.h);
        var s=(i<10?'0'+i:i)+':'
        i=parseInt(v.m);
        s += (i<10?'0'+i:i);
        if(this.showSecond){
            i=parseInt(v.s);
            s+=':'+(i<10?'0'+i:i);
        }
        return s;
      }
   }
},
template: `
<div :style="style" :class="$attrs.class">{{value}}
 <q-popup-proxy cover ref="_tm_ipt_dlg" @hide="confirm" v-if="!disable">
  <q-card><q-card-section>
  <div class="row">
   <div class="col">
    <scroll-select v-model="val.h" :options="hours" width="3em" class="q-px-sm q-py-none"></scroll-select>
   </div>
   <div class="col">
    <scroll-select v-model="val.m" :options="ms" width="3em" class="q-px-sm q-py-none"></scroll-select>
   </div>
   <div class="col" v-if="showSecond">
    <scroll-select v-model="val.s" :options="ms" width="3em" class="q-px-sm q-py-none"></scroll-select>
   </div>
  </div>
  </q-card-section></q-card>
 </q-popup-proxy>
</div>`
}