package com.hmdp.utils;

public interface ILock {
    /*尝试获取锁
    * timeoutSec为锁持有的超时时间，过期后自动释放
    * true为获取成功反之失败
    * */
    boolean tryLock(long timeoutSec);
    /*释放锁*/
    void unLock();
}
