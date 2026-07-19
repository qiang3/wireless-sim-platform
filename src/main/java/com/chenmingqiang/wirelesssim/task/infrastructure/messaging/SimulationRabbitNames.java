package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

/**
 * 仿真任务RabbitMQ资源名称。
 *
 * <p>把交换机、队列和路由键集中管理，避免生产者、消费者和测试分别手写字符串造成拼写不一致。</p>
 */
public final class SimulationRabbitNames {

    /** 接收正常任务执行消息的主直连交换机。 */
    public static final String TASK_EXCHANGE = "simulation.task.exchange";
    /** 把任务执行消息路由到主队列的路由键。 */
    public static final String EXECUTE_ROUTING_KEY = "simulation.task.execute";
    /** Java或Python Worker后续监听的主执行队列。 */
    public static final String EXECUTE_QUEUE = "simulation.task.execute.queue";

    /** 接收需要延迟后再次投递消息的重试交换机。 */
    public static final String RETRY_EXCHANGE = "simulation.task.retry.exchange";
    /** 把临时失败消息路由到重试队列的路由键。 */
    public static final String RETRY_ROUTING_KEY = "simulation.task.retry";
    /** 不设置消费者，消息等待TTL到期后自动回到主交换机。 */
    public static final String RETRY_QUEUE = "simulation.task.retry.queue";

    /** 接收永久失败或超过重试次数消息的死信交换机。 */
    public static final String DEAD_LETTER_EXCHANGE = "simulation.task.dlx";
    /** 把最终失败消息路由到死信队列的路由键。 */
    public static final String DEAD_LETTER_ROUTING_KEY = "simulation.task.dead";
    /** 保存等待人工检查消息的最终死信队列。 */
    public static final String DEAD_LETTER_QUEUE = "simulation.task.dead.queue";

    /** 纯常量工具类不允许创建实例。 */
    private SimulationRabbitNames() {
    }
}
