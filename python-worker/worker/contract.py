"""Java/Python契约转换：保持为纯函数，便于不启动RabbitMQ和模型也能测试。"""

from __future__ import annotations

from typing import Any, Dict, Mapping


class ContractError(ValueError):
    """消息或HTTP响应违反阶段9契约，属于不应无限重试的永久错误。"""


def parse_execution_message(body: bytes) -> Dict[str, Any]:
    """解析Outbox消息并验证Python Worker真正依赖的版本化字段。"""
    import json

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ContractError("消息不是有效UTF-8 JSON") from exc
    required = ("eventId", "taskId", "attemptNo", "eventType", "schemaVersion", "occurredAt")
    missing = [name for name in required if payload.get(name) is None]
    if missing:
        raise ContractError("消息缺少字段: " + ", ".join(missing))
    if payload["eventType"] != "TASK_EXECUTION_REQUESTED" or payload["schemaVersion"] != 1:
        raise ContractError("不支持的消息类型或schemaVersion")
    if int(payload["taskId"]) <= 0 or int(payload["attemptNo"]) <= 0:
        raise ContractError("taskId和attemptNo必须为正数")
    return payload


def evaluation_kwargs(claim_data: Mapping[str, Any], checkpoint: str, output_dir: str) -> Dict[str, Any]:
    """把单位明确的Java场景映射为evaluate_grpo函数参数。"""
    scene = claim_data["scene"]
    evaluation = claim_data["evaluation"]
    if claim_data.get("modelId") != "grpo-rsma-throughput-v1":
        raise ContractError("不支持的modelId")
    if scene.get("accessScheme") != "RSMA" or int(scene.get("deviceCount", 0)) != 3:
        raise ContractError("当前模型只支持3设备RSMA")
    return {
        "checkpoint_path": checkpoint,
        "num_episodes": int(evaluation["numEpisodes"]),
        "max_ep_len": int(evaluation["maxEpisodeLength"]),
        "deterministic": bool(evaluation["deterministic"]),
        "seed": int(evaluation["baseSeed"]),
        "output_dir": output_dir,
        "access_scheme": scene["accessScheme"],
        "device_count": int(scene["deviceCount"]),
        "antenna_count": int(scene["antennaCount"]),
        "N": int(scene["timeSlotCount"]),
        "lambda_arrival": float(scene["dataArrivalRateMegabitPerSlot"]),
        "E_mean": float(scene["averageGreenEnergyMilliJoule"]),
        "B_max": float(scene["batteryCapacityMilliJoule"]),
        "Q_max": float(scene["dataBufferCapacityMegabit"]),
        "P_wpt": float(scene["wptTransmitPowerWatt"]),
        "P_max": float(scene["deviceMaxTransmitPowerMilliWatt"]),
    }


def completion_body(claim_data: Mapping[str, Any], result: Mapping[str, Any]) -> Dict[str, Any]:
    """从标准summary结构生成Java成功回调，AoI没有字段，无法被误写成0。"""
    metrics = result["metrics"]
    model = result["model"]
    evaluation = result["evaluation"]
    if metrics.get("averageAoi") is not None:
        raise ContractError("阶段9吞吐量模型的averageAoi必须为null")
    return {
        "executionId": claim_data["executionId"],
        "modelId": model["modelId"],
        "checkpointSha256": model["checkpointSha256"],
        "baseSeed": evaluation["baseSeed"],
        "throughputMean": metrics["throughputMean"],
        "throughputStd": metrics["throughputStd"],
        "throughputMin": metrics["throughputMin"],
        "throughputMax": metrics["throughputMax"],
        "totalTimesteps": metrics["totalTimesteps"],
        "totalEvaluationTimeSeconds": metrics["totalEvaluationTimeSeconds"],
        "artifactPath": result["artifacts"]["directory"],
    }
