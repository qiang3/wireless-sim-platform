"""RabbitMQ Python Worker：领取任务、运行预训练GRPO评估并回调Java。"""

from __future__ import annotations

import os
import socket
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from eval.evaluate_grpo import evaluate_grpo
from worker.contract import ContractError, completion_body, evaluation_kwargs, parse_execution_message
from worker.java_api import JavaApiError, JavaApiRejected, JavaWorkerApi

EXECUTE_QUEUE = "simulation.task.execute.queue"
RETRY_EXCHANGE = "simulation.task.retry.exchange"
RETRY_ROUTING_KEY = "simulation.task.retry"
DEAD_EXCHANGE = "simulation.task.dlx"
DEAD_ROUTING_KEY = "simulation.task.dead"


class WorkerConfig:
    """从环境变量读取部署配置，密码、令牌和权重路径均不写入Git。"""

    def __init__(self) -> None:
        self.rabbit_host = os.getenv("RABBITMQ_HOST", "localhost")
        self.rabbit_port = int(os.getenv("RABBITMQ_PORT", "5672"))
        self.rabbit_username = os.getenv("RABBITMQ_USERNAME", "wireless")
        self.rabbit_password = os.getenv("RABBITMQ_PASSWORD", "wireless_dev")
        self.rabbit_vhost = os.getenv("RABBITMQ_VHOST", "wireless_sim")
        self.rabbit_heartbeat_seconds = positive_int_env("RABBITMQ_HEARTBEAT_SECONDS", 30)
        self.java_url = os.getenv("JAVA_WORKER_API_URL", "http://localhost:8080")
        self.worker_token = require_env("SIMULATION_WORKER_TOKEN")
        self.checkpoint = str(Path(require_env("GRPO_CHECKPOINT_PATH")).expanduser().resolve())
        self.output_root = Path(os.getenv("GRPO_OUTPUT_ROOT", str(WORKER_ROOT / "artifacts"))).resolve()
        self.worker_id = os.getenv("PYTHON_WORKER_ID", f"python-{socket.gethostname()}")
        self.max_attempts = int(os.getenv("PYTHON_WORKER_MAX_DELIVERY_ATTEMPTS", "3"))


def require_env(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"必须配置环境变量{name}")
    return value.strip()


def positive_int_env(name: str, default: int) -> int:
    """读取必须大于0的整数环境变量，避免误关闭心跳或传入无效连接参数。"""
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"环境变量{name}必须是整数") from exc
    if value <= 0:
        raise RuntimeError(f"环境变量{name}必须大于0")
    return value


def delivery_attempt(headers: Mapping[str, Any] | None) -> int:
    return max(1, int((headers or {}).get("x-delivery-attempt", 1)))


def classify_inference_error(exc: Exception) -> str:
    if isinstance(exc, FileNotFoundError):
        return "MODEL_WEIGHT_NOT_FOUND"
    if isinstance(exc, ContractError) or isinstance(exc, ValueError):
        return "MODEL_OR_SCENE_INCOMPATIBLE"
    if isinstance(exc, RuntimeError) and "checkpoint" in str(exc).lower():
        return "MODEL_WEIGHT_LOAD_FAILED"
    return "INFERENCE_FAILED"


class RabbitGrpoWorker:
    def __init__(self, config: WorkerConfig, api: JavaWorkerApi):
        self.config = config
        self.api = api

    def on_message(self, channel: Any, method: Any, properties: Any, body: bytes) -> None:
        attempt = delivery_attempt(properties.headers)
        try:
            message = parse_execution_message(body)
            self._verify_amqp_properties(message, properties)
            self._process(message)
            channel.basic_ack(method.delivery_tag)
        except (ContractError, JavaApiRejected) as exc:
            self._forward(channel, properties, body, attempt, str(exc), final=True)
            channel.basic_ack(method.delivery_tag)
        except JavaApiError as exc:
            final = attempt >= self.config.max_attempts
            self._forward(channel, properties, body, attempt, str(exc), final=final)
            channel.basic_ack(method.delivery_tag)
        except Exception as exc:
            # 未知消费者故障不修改业务终态，走有限RabbitMQ重试后进入死信。
            final = attempt >= self.config.max_attempts
            self._forward(channel, properties, body, attempt, repr(exc), final=final)
            channel.basic_ack(method.delivery_tag)

    def _process(self, message: Mapping[str, Any]) -> None:
        task_id, attempt_no = int(message["taskId"]), int(message["attemptNo"])
        claim = self.api.claim(task_id, attempt_no, self.config.worker_id)
        outcome = claim["outcome"]
        if outcome in ("ALREADY_HANDLED", "STALE_ATTEMPT"):
            return
        if outcome == "REJECTED":
            raise ContractError(claim.get("detail", "Java拒绝任务"))
        if outcome not in ("CLAIMED", "RESUMABLE"):
            raise ContractError(f"未知领取结论: {outcome}")

        output_dir = self.config.output_root / f"task-{task_id}" / f"attempt-{attempt_no}"
        try:
            result = evaluate_grpo(**evaluation_kwargs(claim, self.config.checkpoint, str(output_dir)))
            self.api.complete(task_id, attempt_no, completion_body(claim, result))
        except (JavaApiError, JavaApiRejected):
            # 回调失败时保留RUNNING状态，让消息重投后通过RESUMABLE再次闭环。
            raise
        except Exception as exc:
            error_body = {
                "executionId": claim["executionId"],
                "errorCode": classify_inference_error(exc),
                "errorMessage": str(exc)[:900] or exc.__class__.__name__,
            }
            self.api.fail(task_id, attempt_no, error_body)

    def _verify_amqp_properties(self, message: Mapping[str, Any], properties: Any) -> None:
        headers = properties.headers or {}
        if properties.message_id != message["eventId"] or properties.type != message["eventType"]:
            raise ContractError("AMQP属性与消息载荷不一致")
        if int(headers.get("attemptNo", -1)) != int(message["attemptNo"]):
            raise ContractError("AMQP attemptNo与载荷不一致")
        if int(headers.get("schemaVersion", -1)) != int(message["schemaVersion"]):
            raise ContractError("AMQP schemaVersion与载荷不一致")

    def _forward(
        self, channel: Any, properties: Any, body: bytes, attempt: int, error: str, *, final: bool
    ) -> None:
        import pika

        headers = dict(properties.headers or {})
        headers["x-delivery-attempt"] = attempt if final else attempt + 1
        headers["x-last-error"] = error[:500]
        headers["x-last-failed-at"] = datetime.now(timezone.utc).isoformat()
        headers["x-final-failure"] = final
        forwarded = pika.BasicProperties(
            content_type=properties.content_type or "application/json",
            delivery_mode=2,
            message_id=properties.message_id,
            type=properties.type,
            timestamp=properties.timestamp,
            headers=headers,
        )
        exchange = DEAD_EXCHANGE if final else RETRY_EXCHANGE
        routing_key = DEAD_ROUTING_KEY if final else RETRY_ROUTING_KEY
        published = channel.basic_publish(
            exchange=exchange, routing_key=routing_key, body=body,
            properties=forwarded, mandatory=True,
        )
        if published is False:
            raise RuntimeError("RabbitMQ未确认重试/死信消息")


def main() -> None:
    import pika

    config = WorkerConfig()
    credentials = pika.PlainCredentials(config.rabbit_username, config.rabbit_password)
    connection = pika.BlockingConnection(pika.ConnectionParameters(
        host=config.rabbit_host, port=config.rabbit_port,
        virtual_host=config.rabbit_vhost, credentials=credentials,
        heartbeat=config.rabbit_heartbeat_seconds, blocked_connection_timeout=30,
    ))
    channel = connection.channel()
    channel.confirm_delivery()
    channel.basic_qos(prefetch_count=1)
    worker = RabbitGrpoWorker(config, JavaWorkerApi(config.java_url, config.worker_token))
    channel.basic_consume(queue=EXECUTE_QUEUE, on_message_callback=worker.on_message, auto_ack=False)
    print(f"[PYTHON-WORKER] id={config.worker_id}, queue={EXECUTE_QUEUE}, checkpoint={config.checkpoint}")
    try:
        channel.start_consuming()
    except KeyboardInterrupt:
        if channel.is_open:
            channel.stop_consuming()
    finally:
        if connection.is_open:
            connection.close()


if __name__ == "__main__":
    main()
