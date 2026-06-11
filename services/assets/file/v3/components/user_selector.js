//一边输入帐号，一边过滤用户的组件
export default {
data() {return {
    opts:[],//选择项，调用search获得
    oldLen:0,
    values:null //选中项，不能赋值[]，会显示一个空选项
}},
props: {
    accounts:{type:Array,required:true}, //复杂对象，可以直接在组件中修改
    label:{type:String,required:true},
    multi:{type:Boolean,required:false,default:true}, //:multi="false"
    service:{type:String,required:false,default:''}, //不为空时，只查服务授权过的用户
    roles:{type:Array,required:false,default:[]}, //不为空时，只返回指定角色，需service配合
    useid:{type:Boolean,required:false,default:false},//是否返回用户ID,:useid="true"
    dense:{type:Boolean,required:false,default:false}
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
    var url;
    if(this.service=='') {
      url="/api/user/search?limit=10&s="+val;
    } else {
      url="/api/power/search?limit=10&service="+this.service+"&s="+val;
    }
    request({method:"GET",url:url}, SERVICE_USER).then(resp => {
        if(resp.code!=RetCode.OK) {
            this.opts=[]
            return;
        }

        var opts=[];
        var user, val;
        var cols=resp.data.cols;
        for(var u of resp.data.users) {
            user={};
            for(var i in cols) {
                user[cols[i]]=u[i];
            }
            if(user.role) {
                if(this.roles.length>0&&!this.roles.includes(user.role)) {
                    continue;//未包括此角色
                }
            }
            val=this.useid ? user.id : user.account;
            opts.push({value:val, label:user.account+' '+user.nickName});
        }
        this.opts=opts;
    })
  })
},
input_user(val, done) { //添加选项
  if (val.length > 0) {
     done(val, 'add-unique')
  }
},
changed() {
    //因为无label，用emit-value会导致selected-item无法显示
    //所以接受改变的事件，将选中项的value存入accounts属性
    //因为accounts是数组，所以此属性可以更改，尽管不优雅，但是管用，不必$emit
    this.accounts.splice(0, this.accounts.length)
    if(this.multi) {
        for(var c of this.values) {
            this.accounts.push(c.value);
        }
    } else if(this.values){ //单选时，values不是数组
        this.accounts.pop();//先删除，再添加
        this.accounts.push(this.values.value);
    }
}
},
template: `
<q-select v-model="values" :label="label" :options="opts"
  use-input use-chips :multiple="multi"
  hide-dropdown-icon input-debounce=200
  @new-value="input_user" @filter="get_opts"
  @update:model-value="changed" :dense="dense">
 <template v-slot:selected-item="scope">
  <q-chip removable dense @remove="scope.removeAtIndex(scope.index)"
    :tabindex="scope.tabindex" class="q-ma-none">
    {{scope.opt.label}}
  </q-chip>
 </template>
</q-select>
`
}