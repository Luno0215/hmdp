package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData<T> {
    private LocalDateTime expireTime;   // 过期时间
    private T data;        // 要存的数据，使用泛型避免多次反序列化
}
