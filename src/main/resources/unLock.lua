-- 这里的KEYS[1]就是传入锁的key
-- 这里的ARGV[1]就是线程标识
-- 比较锁中的线程标识与线程标识是否一致
if(redis.call('get',KEYS[1])==ARGV[1])then
    return redis.call('delete',KEYS[1])
end
return 0