package com.xh.mall.util;

import com.xh.mall.util.exception.InventoryShortageException;

public class RetryUtils {

    /**
     * 手动重试执行器
     * @param action      需要重试的业务逻辑（需返回布尔值表示是否成功）
     * @param maxAttempts 最大重试次数
     * @param delayMillis 重试间隔（毫秒）
     * @return 最终执行结果
     */
    public static boolean executeWithRetry(
            OrderOperation action,  // 使用新接口名
            int maxAttempts,
            long delayMillis
    ) {
        int attempts = 0;
        while (attempts < maxAttempts) {
            try {
                if (action.attempt()) {
                    return true;
                }
            } catch (InventoryShortageException e) {
                // 捕获特定异常，其他异常直接抛出
                if (attempts == maxAttempts - 1) {
                    throw e;
                }
            }
            attempts++;
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("重试中断", e);
            }
        }
        throw new InventoryShortageException("操作失败，已达最大重试次数: " + maxAttempts);
    }

    @FunctionalInterface
    public interface OrderOperation {
        boolean attempt() throws InventoryShortageException; // 方法名改为 attempt
    }
}