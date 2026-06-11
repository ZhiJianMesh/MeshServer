//一边输入名称，一边过滤服务的组件
export default {
data() {return {
    opts:[],//选择项，调用search获得
    oldLen:0,
    values:null //选中项，不能赋值[]，会显示一个空选项
}},
props: {
    services:{type:Array,required:true}, //复杂对象，可以在组件中修改
    label:{type:String,required:false,default:''},
    type:{type:String,required:false,default:"enterprise"},
    multi:{type:Boolean,required:false,default:true}, //:multi="false"
    extOpt:{type:Object,required:false,default:null}, //扩展选择，比如“{label:'所有服务',value:'*'}”
    useid:{type:Boolean,required:false,default:false} //是否返回服务ID,:useid="true"
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
    var opts={method:"GET",url:"/api/service/search?limit=10&s="+val+"&type="+this.type};
    request(opts, "appstore").then(resp => {
        if(resp.code!=RetCode.OK) {
            this.servicepOpts=[]
            return;
        }
        var opts=[];
        var idIdx=findInArray(resp.data.cols, 'id');
        var srvIdx=findInArray(resp.data.cols, 'service');
        var dispIdx=findInArray(resp.data.cols, 'displayName');
        var val;
        for(var s of resp.data.services) {
            val=this.useid ? s[idIdx] : s[srvIdx]
            opts.push({value:val, label:s[dispIdx]});
        }
        if(this.extOpt) {
            opts.push(this.extOpt);
        }
        this.opts=opts;
    })
  })
},
input_service(val, done) { //添加选项
  if (val.length > 0) {
     done(val, 'add-unique')
  }
},
changed() {
    //因为无label，用emit-value会导致selected-item无法显示
    //所以在此接受改变的事件，将选中项的value存入services属性
    //又因为services是数组，所以此属性可以在组件中更改，尽管不优雅，但是管用，不必$emit
    this.services.splice(0, this.services.length)
    if(this.multi) {
        for(var c of this.values) {
            this.services.push(c.value);
        }
    } else if(this.values){ //单选时，values不是数组
        this.services.push(this.values.value);
    }
}
},
template: `
<q-select v-model="values" :label="label" :options="opts"
  use-input use-chips @update:model-value="changed"
  hide-dropdown-icon input-debounce=200 dense :multiple="multi"
  @new-value="input_service" @filter="get_opts">
 <template v-slot:selected-item="scope">
  <q-chip removable dense @remove="scope.removeAtIndex(scope.index)"
    :tabindex="scope.tabindex" class="q-ma-none">
    {{scope.opt.label}}
  </q-chip>
 </template>
</q-select>
`
}