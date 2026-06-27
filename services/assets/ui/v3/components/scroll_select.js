//滚动选择组件
export default{
data() {return {
  chkIdx:-1,
  top:0,
  opts:[]
}},
props: {
  modelValue:{type:String},
  height:{type:String,required:false,default:"5em"},
  width:{type:String,required:false,default:"10em"},
  chkedStyle:{type:Object,required:false,default:{'background-color':'blue',color:'white'}},
  itemClass:{type:String,required:false,default:"text-center"},
  options:{type:Array,required:true} //选项[{label:xx,value:yyy}...]
},
emits: ['update:modelValue'],
watch:{
    modelValue(nv,_ov) {
        if(nv!=this.opts[this.chkIdx].value) {
            this.scrollToCheck();
        }
    }
},
created(){
    var opts=[];
    var opt;
    for(var o of this.options) {
        opt=(o.label&&o.value) ? o : {label:o, value:o};
        opt.style={};
        opts.push(opt);
    }
    this.opts=opts;
},
mounted() {
    this.scrollToCheck();
    if(this.chkIdx==0) {//大于0的情况，肯定设置过，不必修改父组件的值
        this.$emit('update:modelValue', this.opts[this.chkIdx].value);
    }
},
methods: {
scrollToCheck() {
    if(this.chkIdx>=0)this.opts[this.chkIdx].style={};
    var i=0;
    this.chkIdx=-1;
    for(var o of this.opts) {
        if(o.value==this.modelValue) {
            o.style=this.chkedStyle;
            this.chkIdx=i;
            break;
        }
        i++;
    }
    if(this.chkIdx<0){
        this.chkIdx=0;
    }
    this.top=this.chkIdx;
    var s=this.$refs._scroll_area.getScroll();
    var v=parseInt(this.top*s.verticalSize/this.opts.length);
    this.$refs._scroll_area.setScrollPosition ('vertical', v); 
},
select(idx) {
    this.opts[this.chkIdx].style={};
    this.opts[idx].style=this.chkedStyle;
    this.$emit('update:modelValue', this.opts[idx].value);
    this.chkIdx=idx;
}
},
template: `
<q-scroll-area :class="$attrs.class" :thumb-style="{display:'none'}"
 :style="{height:height,width:width}" ref="_scroll_area">
  <div v-for="(opt, i) in opts" :style="opt.style" @click="select(i)" :class="itemClass">
  {{opt.label}}
  </div>
</q-scroll-area>`
}