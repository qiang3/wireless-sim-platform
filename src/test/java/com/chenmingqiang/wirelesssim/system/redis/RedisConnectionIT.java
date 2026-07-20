package com.chenmingqiang.wirelesssim.system.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实Redis验证Spring Data Redis连接、字符串读写和TTL。 */
@SpringBootTest(properties = {
        "simulation.redis.enabled=true",
        "simulation.execution.enabled=false"
})
class RedisConnectionIT {

    /** Spring Boot根据spring.data.redis配置自动创建的字符串操作模板。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 写入短期测试键并读回，最后主动删除，避免集成测试残留数据。 */
    @Test
    void connectsAndReadsTemporaryValue() {
        String key = "wireless-sim:test:connection:" + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(10));
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");
            assertThat(redisTemplate.getExpire(key)).isPositive();
        } finally {
            redisTemplate.delete(key);
        }
    }
}
