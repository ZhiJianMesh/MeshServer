import TimeInput from "./time_input.js"
//日期与时间选择组件
function datetimeToDate(v) {
    var dt=new Date();
    dt.setYear(v.year);
    dt.setMonth(v.month-1);
    dt.setDate(v.day);
    dt.setHours(v.hour);
    dt.setMinutes(v.minute);
    dt.setSeconds(0);
    return dt;
}

function datetimeToStr(v,fmt) {
    var dt=datetimeToDate(v);
    return sysDateToStr(dt,fmt);
}

function sysDateToStr(dt,fmt) {
    var v;
    return fmt
       .replace('YYYY', dt.getFullYear())
       .replace('MM', (v=(dt.getMonth() + 1))>9?v:'0'+v)
       .replace('DD', (v=dt.getDate())>9?v:'0'+v)
       .replace('HH', (v=dt.getHours())>9?v:'0'+v)
       .replace('mm', (v=dt.getMinutes())>9?v:'0'+v)
       .replace('ss', (v=dt.getSeconds())>9?v:'0'+v);
}
export {datetimeToDate, datetimeToStr, sysDateToStr};

export default{
components:{
    "time-input":TimeInput
},
data(){return {
    end:253234080000000/*10000AC*/,
    start:-377485920000000/*10000BC*/,
    val:{year:0,month:0,day:0,hour:0,minute:0},
    dateStr:'',
    old:{year:0,month:0,day:0,hour:0,minute:0}
}},
props: {
    modelValue:{type:String},
    label:{type:String,required:true},
    min:{type:String,default:''},
    max:{type:String,default:''},
    format:{type:String, default:"YYYY/MM/DD HH:mm"},
    weekDays:{type:Array, default:["日","一","二","三","四","五","六"]},
    months:{type:Array, default:["一月","二月","三月","四月","五月","六月","七月","八月","九月","十月","十一月","十二月"]},
    cancel:{type:String, default:'取消'},
    ok:{type:String, default:'确定'},
    showMinute:{type:Boolean, default:true},
    disable:{type:Boolean, default:false}
},
emits: ['update:modelValue'],
created(){
    var dt;
    if(this.max) {
        dt=this.parse(this.max);
        this.end=dt.getTime();
    }
    if(this.min) {
        dt=this.parse(this.min);
        this.start=dt.getTime();
    }
    if(!this.modelValue || typeof this.modelValue==='string') {
        dt=this.parse(this.modelValue);
    } else {
        dt=datetimeToDate(this.modelValue);
    }
    this.val=this.fromDate(dt);
    this.old=this.fromDate(dt);
    this.dateStr=this.val.year+'/'+this.val.month+'/'+this.val.day;
    this.$emit('update:modelValue',this.val);
},
methods:{
rangeFilter(dt) {
    var t=Date.parse(dt);
    return t>this.start&&t<this.end
},
fromDate(dt) {
    return {year:dt.getFullYear(),month:dt.getMonth()+1,day:dt.getDate(),
       hour:dt.getHours(),minute:this.showMinute?dt.getMinutes():0};
},
parse(s) {
    if(!s) {
        s="cur";
    }
    var cfg=s.trim().toLowerCase();
    var dt=new Date();
    if(cfg.startsWith('-')) { //hours
        var v = parseInt(cfg.substring(1));
        dt.setTime(dt.getTime()-v*3600000);
    } else if(cfg.startsWith('+')) {
        var v = parseInt(cfg.substring(1));
        dt.setTime(dt.getTime()+v*3600000);
    } else if(cfg!='cur') {
        dt.setTime(Date.parse(cfg));
    }
    return dt;
},
date_changed(v, r, dtl) {
    if(dtl) { //会发起两次，其中一次v为日期字符串，dtl为空
        this.val.year=dtl.year;
        this.val.month=dtl.month;
        this.val.day=dtl.day;
    }
},
confirm() {
    this.$emit('update:modelValue',this.val);
    var v=this.val;
    this.old={year:v.year,month:v.month,day:v.day,hour:v.hour,minute:v.minute};
    this.$refs._dt_input_dlg.hide();
},
timeChanged(){
    this.val.hour=this.val.time.details.hour;
    this.val.minute=this.val.time.details.minute;
}
},
computed: {
   value: {
      get() {return datetimeToStr(this.val,this.format)},
      set(v) {
        var dt=this.parse(v);
        this.val=this.fromDate(dt);
      }
   },
   oldVal:{
     get() {return datetimeToStr(this.old,this.format)}
   }
},
template: `<q-input dense :label="label" v-model="oldVal" readonly :disable="disable">
<template v-slot:append>
<q-icon name="event" :class="$attrs.class">
 <q-popup-proxy cover transition-show="scale" transition-hide="scale" ref="_dt_input_dlg">
 <q-card>
  <q-card-section>
   <div class="text-h6">{{value}}</div>
  </q-card-section>
  <q-separator></q-separator>
  <q-card-section>
   <q-date minimal flat v-model="dateStr" :mask="format" emit-immediately
   :locale="{daysShort:weekDays,months:months,monthsShort:months}"
   :options="rangeFilter" @update:model-value="date_changed">
   </q-date>
   <q-separator></q-separator>
   <div class="row justify-center text-center text-h6">
    <time-input v-model="val.time" @update:model-value="timeChanged" :showSecond="false"></time-input>
   </div>
  </q-card-section>
  <q-card-actions align="right">
   <q-btn flat v-close-popup :label="cancel" color="primary"></q-btn>
   <q-btn :label="ok" color="primary" @click="confirm"></q-btn>
  </q-card-actions>
 </q-card>
 </q-popup-proxy>
</q-icon>
</template>
</q-input>`
}