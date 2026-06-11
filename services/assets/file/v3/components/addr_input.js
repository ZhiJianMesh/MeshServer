//一边输入地址，一边过滤地址的组件
function addrToStr(v) {
    var s=v.province;
    if(v.city)s+='/'+v.city;
    if(v.county)s+='/'+v.county;
    return s;
}
export {addrToStr};
export default {
data() {return {
    opts:[],
    maps:[],
    oldLen:0
}},//选择项，调用search获得
props: {
    modelValue:{type:Object,required:true},
    label:{type:String,required:false,default:''}
},
emits: ['update:modelValue'],
created(){
    if(typeof(this.modelValue)==='string') {
        var ss=this.modelValue.split(/[\/,-,., ]+/);
        var a={};
        if(ss.length>0)a.province=ss[0];
        if(ss.length>1)a.city=ss[1];
        if(ss.length>2)a.county=ss[2];
        this.$emit('update:modelValue', a);
    }
},
methods:{
get_opts(val,update) {
  var ol=this.oldLen;
  this.oldLen=val.length;
  if(val==='') {
    update(() => {
      this.opts=[]
    })
    return;
  }
  update(() => {
    if(val.length>1) {
      if(ol>0 && this.opts.length==0 && val.length>ol) {
        return;//已有的输入找不到，更多的输入更找不到
      }
    }
    var opts={method:"GET",url:"/api/search?limit=10&s="+val,private:false,cloud:true};
    request(opts, SERVICE_ADDR).then(resp=>{
        if(resp.code!=RetCode.OK) {
            this.opts=[];
            return;
        }
        var opts=[];
        var al=resp.data.addrs;//[{id,fid,zip,name,fName}...]
        for(var i in al) {
            opts.push({value:i,label:al[i].fName+'('+al[i].zip+')'});
        }
        this.maps=al;
        this.opts=opts;
    })
  })
}
},
computed: {
value: {
  get() {
    var m=this.modelValue;
    if(!m.province) {
        return null;
    }
    return addrToStr(m);
  },
  set(v) {//选中项的编号，从0开始
    var a=this.maps[v];
    if(!a) return;

    var ss=a.fName.split(/[\/,-,., ]+/);
    var addr={code:a.zip}
    if(ss.length>0) {
        addr.province=ss[0];
    }
    if(ss.length>1) {
        addr.city=ss[1];
    }
    if(ss.length>2) {
        addr.county=ss[2];
    }
    this.$emit('update:modelValue', addr);
  }
}
},
template: `<q-select :label="label" :options="opts" v-model="value"
  use-input emit-value hide-dropdown-icon :multiple=false
  input-debounce=500 dense @filter="get_opts">
  <template v-slot:no-option>
   <q-item><q-item-section class="text-grey">
     No options
   </q-item-section></q-item>
  </template>
  </q-select>`
}