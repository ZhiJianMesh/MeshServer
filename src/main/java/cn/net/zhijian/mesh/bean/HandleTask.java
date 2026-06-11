package cn.net.zhijian.mesh.bean;

import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cn.net.zhijian.mesh.frm.RetCode;
import cn.net.zhijian.mesh.frm.intf.IThreadPool;
import cn.net.zhijian.util.LogUtil;

public class HandleTask implements IThreadPool {
    private static final Logger LOG = LogUtil.getInstance();

    public final String name;
    public final CompletableFuture<HandleResult> future;

    public HandleTask(String name, CompletableFuture<HandleResult> future) {
        this.name = name;
        this.future = future;
    }


    /**
     * 等待一个future列表全部运行结束，并逐个处理异步返回
     * @param func 函数名称
     * @param blockMs 阻塞时间(毫秒)
     * @param tasks future列表
     * @param ignores 可忽略的错误码
     * @return 成功与否
     */
    public static boolean waitHolder(String func, int blockMs, List<HandleTask> tasks, Set<Integer> ignores) {
        int i = 0;
        CompletableFuture<?>[] futures = new CompletableFuture[tasks.size()];
        for(HandleTask t : tasks) {
            futures[i++] = t.future.whenCompleteAsync((hr, e) -> {
                if(e != null) {
                    LOG.error("Fail to call {}.{}", t.name, func, e);
                }
            }, Pool);
        }

        CompletableFuture<Void> holder = CompletableFuture.allOf(futures);
        try {
            holder.join(); //等待所有任务都结束，包括抛出异常的
        } catch (Exception e) {
            LOG.error("Fail to call {}", func, e);
            return false;
        }

        int errNum = 0;
        for(HandleTask t : tasks) {
            if(t.future.isCompletedExceptionally()) {
                LOG.error("Fail to execute {}.{}, exception happened", t.name, func);
                errNum++;
                continue; //不结束循环，而是将所有的错误都输出一遍
            }

            try {
                HandleResult hr = t.future.get(blockMs, TimeUnit.MILLISECONDS);
                if(ignores != null && ignores.contains(hr.code)) {
                    LOG.warn("Fail to call {}.{},result:{},but ignore it", t.name, func, hr.brief());
                } else if(hr.code != RetCode.OK) {
                    LOG.error("Fail to call {}.{}, result:{}", t.name, func, hr.brief());
                    errNum++;
                } else {
                    LOG.info("Success to call {}.{}", t.name, func);
                }
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                errNum++;
                LOG.error("Fail to get result of {}.{}", t.name, func, e);
            }
        }

        return errNum == 0;
    }

    public static boolean waitHolder(String func, int blockMs, List<HandleTask> tasks) {
        return waitHolder(func, blockMs, tasks, null);
    }
}
