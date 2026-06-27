//一边输入地址，一边过滤地址的组件
export default {
data() {return {
    addr:{province:{id:0,name:''},city:{id:0,name:''},county:{id:0,name:'',zip:''}},
    provinces:[],
    cities:[],
    counties:[],
    addrDlg:false,
    cache:{}
}},//选择项，调用search获得
props: {
    modelValue:{type:Object,required:true},
    country:{type:String,required:false,default:'156'},//156为中国
    label:{type:String,required:false,default:''},
    ok:{type:String,default:"确定"},
    close:{type:String,default:"关闭"}
},
emits: ['update:modelValue', 'confirm:modelValue'],
created(){
    this.subs(this.country,list=>{
        this.provinces=list;
        if(!this.modelValue.province) {
            this.addr.province=list[0];
            this.setProvince(list[0]);
        }
    });
    if(this.modelValue.province) {
        this.addr.province.name=this.modelValue.province;
        this.addr.city.name=this.modelValue.city;
        this.addr.county.name=this.modelValue.county;
    }
},
methods:{
subs(fid,cb) {
    if(this.cache[fid]) {
        cb(this.cache[fid]);
        return;
    }
    var opts={method:"GET",url:"/api/sub?fid="+fid+"&offset=0&num=1000",private:false,cloud:true};
    request(opts, "address").then(resp=>{
        if(resp.code!=RetCode.OK) {
            cb([]);
        } else {
            this.cache[fid]=resp.data.addrs;
            cb(resp.data.addrs);
        }
    })
},
setProvince(p) {
    this.addr.province=p;
    this.subs(p.id,list=>{
        this.cities=list;
        if(list.length>0) {
            this.addr.city=list[0];
            this.setCity(list[0]);
        } else {
            this.addr.city={id:0,name:''};
            this.counties=[];
        }
    });
},
setCity(c) {
    this.addr.city=c;
    this.subs(c.id,list=>{
        this.counties=list;
        if(list.length>0) {
            this.addr.county=list[0];
            this.setCounty(list[0]);
        } else {
            this.addr.county={id:0,name:'',zip:''};
        }
    });
},
setCounty(c) { //县|区
    this.addr.county=c;
},
confirm(){
    var addr={
        code:this.addr.county.zip, //邮政编码
        province:this.addr.province.name,
        city:this.addr.city.name,
        county:this.addr.county.name
    }
    this.$emit('confirm:modelValue', addr);
    this.addrDlg=false;
}
},
computed: {
value: {
  get() {
    return this.modelValue.province+' '+this.modelValue.city+' '+this.modelValue.county;
  }
}
},
template: `
<div class="row justify-start items-center q-gutter-lg" @click="addrDlg=true">
 <div v-if="label">{{label}}</div>
 <div>{{value}}</div>
</div>

<q-dialog v-model="addrDlg">
  <q-card style="width:70vw;">
   <q-card-section><div class="text-h6">{{label}}</div></q-card-section>
   <q-card-section class="q-pt-none">
    <q-breadcrumbs separator='/'>
      <q-breadcrumbs-el :label="addr.province.name"></q-breadcrumbs-el>
      <q-breadcrumbs-el :label="addr.city.name"></q-breadcrumbs-el>
      <q-breadcrumbs-el :label="addr.county.name"></q-breadcrumbs-el>
    </q-breadcrumbs>
    <div class="q-ma-sm row item-start no-wrap">
      <q-scroll-area style="max-width:30vw;height:50vh;" class="col" visible>
       <q-list dense>
        <q-item v-for="p in provinces" @click="setProvince(p)" clickable
        manual-focus :focused="p.id==addr.province.id">
         <q-item-section>{{p.name}}</q-item-section>
        </q-item>
       </q-list>
      </q-scroll-area>
      <q-scroll-area style="max-width:30vw;height:50vh;" class="col" visible>
       <q-list dense>
        <q-item v-for="c in cities" @click="setCity(c)" clickable
         manual-focus :focused="c.id==addr.city.id">
         <q-item-section>{{c.name}}</q-item-section>
        </q-item>
       </q-list>
      </q-scroll-area>
      <q-scroll-area style="max-width:40vw;height:50vh;" class="col">
       <q-list dense>
        <q-item v-for="c in counties" @click="setCounty(c)" clickable
         manual-focus :focused="c.id==addr.county.id">
         <q-item-section>{{c.name}}</q-item-section>
        </q-item>
       </q-list>
      </q-scroll-area>
     </div> 
    </q-card-section>
    <q-card-actions align="right" class="q-pr-md">
     <q-btn flat :label="close" color="primary" v-close-popup></q-btn>
     <q-btn :label="ok" color="primary" @click="confirm"></q-btn>
    </q-card-actions>
 </q-card>
</q-dialog>
`
}