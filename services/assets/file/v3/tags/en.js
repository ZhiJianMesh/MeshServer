const __err_infos={
'0':"Success",
'1':"deprecated",
'100':"system internal errror",
'101':"network error",
'102':"no token",
'103':"null request",
'104':"database error",
'105':"invalid token or not login",
'106':"service not found",
'107':"system busy",
'108':"request timeout",
'109':"api not supported",
'110':"feature not exists",
'111':"no right to execute the operation",
'112':"no valid node",
'113':"request a irrelevant node",
'114':"third party error",
'115':"balance unsufficient",
'118':'request has been denied',
'150':"unknown error",
'2000':"not exists",
'2001':"already exists",
'3000':"api error",
'3001':"invalid requst body",
'3002':"version not match",
'3003':"in an wrong state",
'4000':"parameter error",
'5000':"service exception",
'5001':"service not in a valid state",
'100000':"client fail to handle",
'unknown':"unknown"
};

function date2str(dt) {//MM-dd-yyyy
  return ((v=dt.getMonth()+1)>9?v:'0'+v)
   + '/' + ((v=dt.getDate())>9?v:'0'+v)
   + '/' + dt.getFullYear();
}

function datetime2str(dt,withSec) { //MM/dd/yyyy HH:mm
  var v;
  var s = ((v=dt.getMonth()+1)>9?v:'0'+v)
   + '/' + ((v=dt.getDate())>9?v:'0'+v)
   + '/' + dt.getFullYear()
   + ' ' + ((v=dt.getHours())>9?v:'0'+v)
   + ':' + ((v=dt.getMinutes())>9?v:'0'+v);
  if(withSec) {
    s+=':'+((v=dt.getSeconds())>9?v:'0'+v);
  }
  return s;
}
