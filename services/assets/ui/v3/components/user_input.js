//一边输入帐号，一边过滤用户的组件，返回{id:xxx,account:yyyyy}
export default {
data() {return {
    opts:[],//选择项，调用search获得
    maps:{},
    oldLen:0,
    values:null //选中项，不能赋值[]，会显示一个空选项
}},
props: {
    modelValue:{type:Object,required:true}, //{id:xxx,account:'yyy',nickName:'zzz'}
    label:{type:String,required:true}
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
        return;//已有的输入找不到，更多的输入更找不到；如果原有的输入没来得及触发查询，仍然要查询
      }
    }
    //id,account,nickName,type,sex,ustatus
    var url="/api/user/search?limit=10&s="+val;
    request({method:"GET",url:url}, SERVICE_USER).then(resp => {
        if(resp.code!=RetCode.OK) {
            this.opts=[]
            return;
        }

        var opts=[];
        var user;
        var maps=[];
        var cols=resp.data.cols;
        var n=0;
        for(var u of resp.data.users) {
            user={};
            for(var i in cols) {
                user[cols[i]]=u[i];
            }
            opts.push({value:(n++), label:user.account+' '+user.nickName});
            maps.push(user);
        }
        this.opts=opts;
        this.maps=maps;
    })
  })
}
},
emits: ['update:modelValue'],
computed: {
value: {
  get() {
    var u=this.modelValue;
    if(!u.id || u.id<=0) {
        return null;
    }
    return u.account+' '+u.nickName;
  },
  set(v) {//选中项的编号，从0开始
    var a=this.maps[v];
    if(!a) return;
    this.$emit('update:modelValue', a);
  }
}
},
template: `<q-select :label="label" :options="opts" v-model="value"
  use-input emit-value hide-dropdown-icon :multiple=false
  input-debounce="200" dense @filter="get_opts">
  <template v-slot:no-option>
   <q-item><q-item-section class="text-grey">
     No options
   </q-item-section></q-item>
  </template>
</q-select>
`
}