//日期选择组件component-date-input
export default{
data(){return {end:0,start:0}},
props: {
    modelValue:{type:String,required:true},
    label:{type:String,required:true},
    min:{type:String,default:''},
    max:{type:String,default:''},
    dateFormat:{type:String, default:"YYYY/MM/DD"},
    weekDays:{type:Array, default:["日","一","二","三","四","五","六"]},
    months:{type:Array, default:["一月","二月","三月","四月","五月","六月","七月","八月","九月","十月","十一月","十二月"]},
    close:{type:String, default:'关闭'},
    disable:{type:Boolean, default:false}
},
emits: ['update:modelValue'],
created(){
    this.end=this.parse(this.max,253234080000000/*10000AC*/);
    this.start=this.parse(this.min,-377485920000000/*10000BC*/);
},
methods:{
rangeFilter(dt) {
    var t=Date.parse(dt);
    return t>this.start&&t<this.end
},
parse(s,def) {//ms
    if(!s)return def;
    if(s=="today") {
        return Date.now();
    }
    var cfg=s.trim().toLowerCase();
    var dt=new Date();
    if(cfg.startsWith('-')) { //days
        var v = parseInt(cfg.substring(1));
        dt.setTime(dt.getTime()-v*86400000);
    } else if(cfg.startsWith('+')) {
        var v = parseInt(cfg.substring(1));
        dt.setTime(dt.getTime()+v*86400000);
    } else {
        dt.setTime(Date.parse(cfg));
    }
    return dt.getTime();
}
},
computed: {
   value: {
      get() {return this.modelValue},
      set(v) {
          if(this.rangeFilter(v)) {
            this.$emit('update:modelValue', v)
          }
      }
   }
},
template: `<q-input dense :label="label" v-model="value" readonly :disable="disable">
<template v-slot:append>
<q-icon name="event" :class="$attrs.class">
 <q-popup-proxy cover transition-show="scale" transition-hide="scale">
  <q-date minimal v-model="value" :mask="dateFormat" emit-immediately
   :locale="{daysShort:weekDays,months:months,monthsShort:months}"
   :options="rangeFilter">
    <div class="row items-center justify-end">
     <q-btn v-close-popup :label="close" color="primary"></q-btn>
    </div>
  </q-date>
 </q-popup-proxy>
</q-icon>
</template>
</q-input>`
}