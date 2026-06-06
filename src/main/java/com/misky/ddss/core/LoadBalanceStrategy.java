package com.misky.ddss.core;

/**
 * 数据源负载均衡策略
 *
 * @see GroupDataSource
 */
public enum LoadBalanceStrategy {

    /** 轮询：依次选择组成员 */
    ROUND_ROBIN,

    /** 随机：每次随机选择一个成员 */
    RANDOM
}
