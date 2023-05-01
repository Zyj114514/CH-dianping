package com.hmdp.service;

public interface ILock {

    /**
     * 获取锁
     * 设置锁释放时间
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
