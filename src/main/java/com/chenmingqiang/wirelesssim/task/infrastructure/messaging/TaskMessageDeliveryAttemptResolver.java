package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** 读取应用维护的消息处理次数；Outbox首次消息没有Header时按第1次处理。 */
@Component
public class TaskMessageDeliveryAttemptResolver {

    /** 项目自定义的总处理次数Header。 */
    public static final String DELIVERY_ATTEMPT_HEADER = "x-delivery-attempt";

    /** 返回当前处理次数，Header非法时视为永久消息契约错误。 */
    public int resolve(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(DELIVERY_ATTEMPT_HEADER);
        if (value == null) {
            return 1;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidTaskMessageException("x-delivery-attempt必须是数字");
        }
        long attempt = number.longValue();
        if (attempt < 1 || attempt > Integer.MAX_VALUE) {
            throw new InvalidTaskMessageException("x-delivery-attempt超出有效范围");
        }
        return (int) attempt;
    }
}
