# 项目演示截图目录

本目录保存阶段10.2真实运行证据。截图来自任务`489`的演示链路，按实际操作顺序编号。

## 实际文件映射

| 文件 | 证明内容 | 公开状态 |
|---|---|---|
| `00.png` | Docker中的MySQL、RabbitMQ、Redis正在运行 | 可公开 |
| `01.png` | 演示前3个RabbitMQ队列为空 | 可公开 |
| `02.png` | Java调试环境变量 | 可公开，包含仅用于本地演示的Worker Token |
| `03.png` | 创建3设备RSMA场景及成功响应 | 可公开 |
| `04.png` | 场景参数写入MySQL | 可公开 |
| `05.png` | 提交任务的Authorization占位符和幂等键 | 可公开 |
| `06.png` | GRPO任务提交后返回PENDING | 可公开 |
| `07.png` | `experiment_task=PENDING` | 可公开 |
| `08.png` | `outbox_event=PUBLISHED` | 可公开 |
| `09.png` | RabbitMQ主队列`Ready=1、Unacked=0` | 可公开 |
| `10.png` | Python调试配置，个人路径已遮盖 | 可公开，包含仅用于本地演示的Worker Token |
| `11.png` | `experiment_task=RUNNING` | 可公开 |
| `12.png` | `task_execution=RUNNING`和业务心跳 | 可公开 |
| `13.png` | 运行中尚无`simulation_result` | 可公开 |
| `14.png` | RabbitMQ主队列`Ready=0、Unacked=1` | 可公开，README核心图 |
| `15.png` | CUDA加载GRPO权重并完成10回合评估 | 可公开，README核心图 |
| `16.png` | `experiment_task=SUCCEEDED/100%` | 可公开 |
| `17.png` | `outbox_event=PUBLISHED`且无错误 | 可公开 |
| `18.png` | `task_execution=SUCCEEDED` | 可公开 |
| `19.png` | 唯一结果、吞吐量和GRPO元数据写入MySQL | 可公开，README核心图 |
| `20.png` | 用户结果API返回完整预训练模型元数据 | 可公开，README核心图 |
| `21-java-test.png` | Java 97项自动化测试通过 | 可公开 |
| `22-python-test.png` | Python 10项测试通过 | 可公开，个人项目路径已移除 |

## 公开说明

截图已经完成逐张核对，可以随项目公开提交。`02.png`和`10.png`展示的
`wireless-worker-local-secret`是README中公开说明的本地演示凭据，不用于生产环境。

如果未来将项目部署到公网或生产环境，必须通过密钥管理系统注入新的随机Token，不能继续使用该演示值。

## 图片要求

- PNG格式，建议宽度1200—1600像素；
- 单张尽量不超过500KB；
- 保留关键状态、任务ID、队列名和业务指标；
- 隐藏JWT、真实密钥、密码和个人路径；本地演示Token可以保留，但必须明确标注用途；
- 不对业务数字和状态做内容修改；
- 对照`docs/15-project-demo-guide.md`逐项验收。
