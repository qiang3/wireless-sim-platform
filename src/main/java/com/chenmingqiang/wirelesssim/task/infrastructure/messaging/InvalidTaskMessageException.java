package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

/** 表示消息结构或契约永久非法，重新投递相同消息也无法自行恢复。 */
public class InvalidTaskMessageException extends RuntimeException {

    /** 使用明确原因创建永久消息异常。 */
    public InvalidTaskMessageException(String message) {
        super(message);
    }

    /** 保留反序列化异常作为根因，方便日志定位。 */
    public InvalidTaskMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
