# GRPO预训练模型评估程序

## 当前边界

当前版本只负责加载可信的预训练GRPO权重并评估吞吐量，不在任务执行过程中训练模型。

- 环境：`RSMA_ENV`；
- 设备数量：固定为3；
- 模型维度：观测15维、动作13维；
- 优化目标：吞吐量；
- AoI：不计算，标准结果中为`null`；
- 天线数量：可以作为场景元数据传入，但当前模型不使用；
- 默认评估：10回合、每回合100步、确定性策略、随机种子2026。

环境中的物理参数默认值仍由`env/rsma_env.py`的`DEFAULTS`提供，本次整理没有修改任何默认值。

## 环境

```powershell
conda activate pytorch
python --version
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
```

正式的Conda环境快照是`environment.yml`，核心直接依赖见`requirements.txt`。原始`pip freeze`生成的`requirements-lock.txt`包含Conda构建机和本机wheel路径，因此仅作为本地诊断文件并被Git忽略。正式文件不包含本机Conda安装路径。

## 运行评估

权重和运行结果默认不提交Git。可以使用参数：

```powershell
conda activate pytorch
python python-worker\eval\evaluate_grpo.py `
  --checkpoint "python-worker\pt\GRPO.pt" `
  --output_dir "python-worker\artifacts\manual-eval"
```

也可以使用环境变量避免每次传入路径：

```powershell
$env:GRPO_CHECKPOINT_PATH="D:\WirelessSimData\models\grpo-v1\GRPO.pt"
$env:GRPO_OUTPUT_DIR="D:\WirelessSimData\artifacts\manual-eval"
python python-worker\eval\evaluate_grpo.py
```

## 可覆盖场景参数

未传参数时继续使用环境原有默认值。

| CLI参数 | 环境参数 | 单位 | 原默认值 |
|---|---|---|---:|
| `--device_count` | `num_devices` | 个 | 3，只允许3 |
| `--time_slot_count` | `N` | 时隙 | 100 |
| `--data_arrival_rate` | `lambda_arrival` | Mb/slot | 3 |
| `--average_green_energy` | `E_mean` | mJ | 6 |
| `--battery_capacity` | `B_max` | mJ | 12 |
| `--data_buffer_capacity` | `Q_max` | Mb | 3 |
| `--wpt_transmit_power` | `P_wpt` | W | 4 |
| `--device_max_transmit_power` | `P_max` | mW | 100 |
| `--access_scheme` | 环境类型 | — | RSMA，只允许RSMA |
| `--seed` | 评估种子 | — | 2026 |

示例：显式传入与原默认值相同的场景参数：

```powershell
python python-worker\eval\evaluate_grpo.py `
  --checkpoint "python-worker\pt\GRPO.pt" `
  --output_dir "python-worker\artifacts\explicit-defaults" `
  --access_scheme RSMA `
  --device_count 3 `
  --time_slot_count 100 `
  --data_arrival_rate 3 `
  --average_green_energy 6 `
  --battery_capacity 12 `
  --data_buffer_capacity 3 `
  --wpt_transmit_power 4 `
  --device_max_transmit_power 100
```

## 标准结果

每次评估会在输出目录生成`summary.json`，主要结构为：

```json
{
  "schemaVersion": 1,
  "model": {
    "modelId": "grpo-rsma-throughput-v1",
    "algorithm": "GRPO",
    "checkpointSha256": "..."
  },
  "evaluation": {
    "mode": "PRETRAINED_MODEL",
    "trainingPerformed": false,
    "deterministic": true
  },
  "scenario": {
    "accessScheme": "RSMA",
    "deviceCount": 3
  },
  "metrics": {
    "throughputUnit": "Mbit/episode",
    "throughputMean": 0.0,
    "throughputStd": 0.0,
    "averageAoi": null
  }
}
```

## 测试

兼容性测试不需要权重；真实复现测试通过环境变量指定可信本地权重：

```powershell
$env:GRPO_TEST_CHECKPOINT=(Resolve-Path "python-worker\pt\GRPO.pt").Path
conda run -n pytorch python -m unittest discover -s python-worker\tests -v
```

复现测试只比较吞吐量等业务指标，不比较每次运行必然不同的耗时。

## 启动RabbitMQ Worker

先让Java应用使用Python模式并配置相同的内部令牌，然后在Conda环境中启动：

```powershell
$env:SIMULATION_WORKER_TOKEN="请替换为随机长令牌"
$env:GRPO_CHECKPOINT_PATH=(Resolve-Path "python-worker\pt\GRPO.pt").Path
$env:JAVA_WORKER_API_URL="http://localhost:8080"
$env:PYTHON_WORKER_ID="python-local-1"
$env:RABBITMQ_HEARTBEAT_SECONDS="300" # 仅长时间断点调试需要；正常运行不设置，默认30秒
conda activate pytorch
python python-worker\worker\main.py
```

Python消费者使用`prefetch=1`和手动ACK。只有Java成功保存结果、失败终态或确认消息已处理后才确认原消息；Java暂时不可访问时使用阶段8的延迟重试与死信拓扑。
