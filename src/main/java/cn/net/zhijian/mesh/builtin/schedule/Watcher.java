package cn.net.zhijian.mesh.builtin.schedule;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import cn.net.zhijian.mesh.bean.CompanyInfo;
import cn.net.zhijian.mesh.bean.HandleResult;
import cn.net.zhijian.mesh.client.HttpClient.ServiceReqBuilder;
import cn.net.zhijian.mesh.client.ServiceClient;
import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.abs.AbsServerResponse;
import cn.net.zhijian.mesh.frm.config.ServiceInfo;
import cn.net.zhijian.mesh.frm.intf.IServiceWatcher.DefaultServiceWatcher;
import cn.net.zhijian.mesh.frm.intf.IServiceServer;
import cn.net.zhijian.mesh.server.TimerKeeper;
import cn.net.zhijian.mesh.server.TimerKeeper.TimerTaskWrapper;
import cn.net.zhijian.util.DateUtil.PeriodType;
import cn.net.zhijian.util.LogUtil;
import cn.net.zhijian.util.StringUtil;
import cn.net.zhijian.util.UrlPathInfo;
import cn.net.zhijian.util.ValParser;


/**
 * 用于初始化各家oss服务的客户端以及logo文件读取接口
 * @author flyinmind of csdn.net
 */
public final class Watcher extends DefaultServiceWatcher {
    private static final Logger LOG = LogUtil.getInstance();
    private static final Logger ACCESS = LogUtil.getInstance(AbsServerResponse.ACCESS_LOGGER_NAME);

    private static final int INTERVAL = 60 * 1000; //每分钟检查一次任务，所以任务的时间单位也是分钟
    private static final String TIMER_TASK = "schedule_task";
    private static int traceId = 0;

    @Override
    public CompletableFuture<HandleResult> afterLoad(IServiceServer server, ServiceInfo si, String pwd) {
        LOG.debug("{}.watcher.afterLoad(),add schedule timer task", si.name);
        
        //需要服务启动完成后再启动定时任务，否则可能服务还未启动，定时任务就开始调用了
        TimerKeeper.addTimerTask(new TimerTaskWrapper( //定期执行任务
            TIMER_TASK,
            PeriodType.CYCLE, INTERVAL,
            () -> {
                execTasks(si);
                execProxies(si);
            }
        ));
        return super.afterLoad(server, si, pwd);
    }
    
    private void execTasks(ServiceInfo caller) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
        req.url("/task/tasks")
           .traceId("schedule_" + (traceId++))
           .cid(CompanyInfo.instance().id)
           .appToken("*");

        ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to get tasks", e);
                return;
            }
            
            if(hr.code != RetCode.OK || hr.data == null) {
                if(hr.code != RetCode.NOT_EXISTS) {
                    LOG.error("Fail to get tasks, result:{}", hr.brief());
                }
                return;
            }
            List<Object> tasks = ValParser.getAsList(hr.data, "tasks");
            if(tasks != null && !tasks.isEmpty()) {
                execTasks(caller, tasks);
            }
        }, Pool);
    }
    
    /**
     * @param caller 调用方，此处就是Schedule
     * @param tasks id,name,type,val,service,url,maxRetry,minTime,
                    leftTimes,nextTime,endTime,interval
     */
    private void execTasks(ServiceInfo caller, List<Object> tasks) {
        String tag = StringUtil.uuid();
        for(Object o : tasks) {
            Map<String, Object> t = ValParser.parseObject(o);
            int taskId = ValParser.getAsInt(t, "id");
            ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
            UrlPathInfo grabUrl = new UrlPathInfo("/task/grab") //抢任务
                    .appendPara("tag", tag, false)
                    .appendPara("id", taskId, false);
            req.url(grabUrl.toString())
                .traceId("schedule_" + (traceId++))
                .cid(CompanyInfo.instance().id)
                .appToken("*");
            
            ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to grab task {}", taskId, e);
                    return;
                }
                
                if(hr.code != RetCode.OK || hr.data == null) {
                    //LOG.error("Fail to grab task {}, result:{}", taskId, hr.brief());
                    return;
                }
                String respTag = ValParser.getAsStr(hr.data, "tag");
                if(tag.equals(respTag)) { //相同说明抢到了执行权
                    execTask(caller, taskId, t, respTag);
//                } else {
//                    LOG.debug("Can't grap task {}", taskId);
                }
            }, Pool);
        }
    }
    
    private void execTask(ServiceInfo caller, int taskId, Map<String, Object> task, String tag) {
        String url = ValParser.getAsStr(task, "url");
        String service = ValParser.getAsStr(task, "service");
        int cid = ValParser.getAsInt(task, "cid");
        String sync = ValParser.getAsStr(task, "sync", "Y");
        String trace = "schedule_" + (traceId++);
        long start = System.currentTimeMillis();
        ServiceReqBuilder req = new ServiceReqBuilder(caller, service);
        UrlPathInfo taskUrl = new UrlPathInfo(url)
                .appendPara("taskId", taskId, false)
                .appendPara("tag", tag, false); //需要传回给schedule，便于问题回溯
        req.url(taskUrl.toString())
           .cid(cid)
           .traceId(trace)
           .appToken("*"); //回调接口tokenChecker必须为APP-schedule或APP-*
        ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
            int code;
            String info;
            int statusCode;
            if(e != null) {
                LOG.error("Fail to call task {}({})", url, taskId, e);
                //method,useTime,statusCode,resultCode,uri,traceId
                code = RetCode.INTERNAL_ERROR;
                info = RetCode.getInfo(code);
                statusCode = 500;
            } else {
                code = hr.code;
                info = hr.info.replace("\"", "\\\"");
                statusCode = 200;
            }
            //method,useTime,statusCode,resultCode,uri,traceId
            ACCESS.info("T\tGET\t{}\t{}\t{}\t{}\t{}", 
                 System.currentTimeMillis() - start, statusCode, code, url, trace);

            StringBuilder body = new StringBuilder(1024);
            body.append("{\"taskId\":").append(taskId)
                .append(",\"code\":").append(code)
                .append(",\"info\":\"").append(info)
                .append("\",\"tag\":\"").append(tag)
                .append("\",\"sync\":\"").append(sync)
                .append("\"}");

            ServiceReqBuilder resultReq = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
            resultReq.url("/task/result")
               .traceId("schedule_" + (traceId++))
               .cid(cid)
               .appToken("*")
               .body(body.toString());
            ServiceClient.servicePost(resultReq);
        }, Pool);        
    }
    
    /**
     * 异步执行委托任务，这类任务是其他服务委托schedule调用的
     * @param caller 服务
     */
    private void execProxies(ServiceInfo caller) {
        ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
        req.url("/proxy/tasks")
           .traceId("proxy_" + (traceId++))
           .cid(CompanyInfo.instance().id) //查询时未使用cid，直接用第一个公司信息
           .appToken("*");

        ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
            if(e != null) {
                LOG.error("Fail to get tasks", e);
                return;
            }
            
            if(hr.code != RetCode.OK || hr.data == null) {
                if(hr.code != RetCode.NOT_EXISTS) {
                    LOG.error("Fail to get aysns, result:{}", hr.brief());
                }
                return;
            }
            List<Object> tasks = ValParser.getAsList(hr.data, "tasks");
            if(tasks != null && !tasks.isEmpty()) {
                execProxies(caller, tasks);
            }
        }, Pool);
    }
    
    /**
     * @param caller 调用方，此处就是Schedule
     * @param tasks id,name,type,val,service,url,maxRetry,minTime,
                    leftTimes,nextTime,endTime,interval
     */
    private void execProxies(ServiceInfo caller, List<Object> tasks) {
        String tag = StringUtil.uuid();
        for(Object o : tasks) {
            Map<String, Object> t = ValParser.parseObject(o);
            int taskId = ValParser.getAsInt(t, "id");
            ServiceReqBuilder req = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
            UrlPathInfo grabUrl = new UrlPathInfo("/proxy/grab") //抢任务
                    .appendPara("tag", tag, false)
                    .appendPara("id", taskId, false);
            req.url(grabUrl.toString())
                .traceId("proxy_" + (traceId++))
                .cid(CompanyInfo.instance().id)
                .appToken("*");
            
            ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to grab async task {}", taskId, e);
                    return;
                }
                
                if(hr.code != RetCode.OK || hr.data == null) {
                    //LOG.error("Fail to grab task {}, result:{}", taskId, hr.brief());
                    return;
                }
                String respTag = ValParser.getAsStr(hr.data, "tag");
                if(tag.equals(respTag)) { //相同说明抢到了执行权
                    execProxy(caller, taskId, t, respTag);
                }
            }, Pool);
        }
    }
    
    private void execProxy(ServiceInfo caller, int taskId, Map<String, Object> task, String tag) {
        String url = ValParser.getAsStr(task, "url");
        String service = ValParser.getAsStr(task, "service");
        int cid = ValParser.getAsInt(task, "cid");
        String trace = "proxy_" + (traceId++);
        long start = System.currentTimeMillis();
        ServiceReqBuilder req = new ServiceReqBuilder(caller, service);
        UrlPathInfo taskUrl = new UrlPathInfo(url)
                .appendPara("taskId", taskId, false)
                .appendPara("tag", tag, false);
        req.url(taskUrl.toString())
           .cid(cid)
           .traceId(trace)
           .appToken("*"); //回调接口tokenChecker必须为APP-schedule或APP-*
        ServiceClient.getPublic(req).whenCompleteAsync((hr, e) -> {
            int code;
            String info;
            int statusCode;
            if(e != null) {
                LOG.error("Fail to call proxy task {}({})", url, taskId, e);
                //method,useTime,statusCode,resultCode,uri,traceId
                code = RetCode.INTERNAL_ERROR;
                info = RetCode.getInfo(code);
                statusCode = 500;
            } else {
                code = hr.code;
                info = hr.info.replace("\"", "\\\"");
                statusCode = 200;
            }
            //method,useTime,statusCode,resultCode,uri,traceId
            ACCESS.info("T\tGET\t{}\t{}\t{}\t{}\t{}", 
                 System.currentTimeMillis() - start, statusCode, code, url, trace);

            StringBuilder body = new StringBuilder(1024);
            body.append("{\"taskId\":").append(taskId)
                .append(",\"code\":").append(code)
                .append(",\"info\":\"").append(info)
                .append("\"}");

            ServiceReqBuilder resultReq = new ServiceReqBuilder(caller, SERVICE_SCHEDULE);
            resultReq.url("/proxy/result")
               .traceId("proxy_" + (traceId++))
               .cid(cid)
               .appToken("*")
               .body(body.toString());
            ServiceClient.servicePost(resultReq);
        }, Pool);        
    }
    
    public CompletableFuture<HandleResult> destroy(IServiceServer server, ServiceInfo si) {
        TimerKeeper.removeTimerTask(TIMER_TASK);
        return super.destroy(server, si);
    }
}
