"""使用Python标准库访问Java内部Worker API，避免再引入HTTP客户端依赖。"""

from __future__ import annotations

import json
from typing import Any, Dict
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class JavaApiError(RuntimeError):
    """Java暂时不可访问或返回5xx；RabbitMQ消息应稍后重试。"""


class JavaApiRejected(ValueError):
    """Java明确拒绝请求；继续重试相同请求没有意义。"""


class JavaWorkerApi:
    def __init__(self, base_url: str, token: str, timeout_seconds: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout_seconds = timeout_seconds

    def claim(self, task_id: int, attempt_no: int, worker_id: str) -> Dict[str, Any]:
        return self._post(task_id, attempt_no, "claim", {"workerId": worker_id})["data"]

    def complete(self, task_id: int, attempt_no: int, body: Dict[str, Any]) -> Dict[str, Any]:
        return self._post(task_id, attempt_no, "complete", body)["data"]

    def fail(self, task_id: int, attempt_no: int, body: Dict[str, Any]) -> Dict[str, Any]:
        return self._post(task_id, attempt_no, "fail", body)["data"]

    def _post(self, task_id: int, attempt_no: int, action: str, body: Dict[str, Any]) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/internal/worker/tasks/{task_id}/attempts/{attempt_no}/{action}"
        request = Request(
            url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json", "X-Worker-Token": self.token},
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            content = exc.read().decode("utf-8", errors="replace")
            if 400 <= exc.code < 500:
                raise JavaApiRejected(f"Java API拒绝请求({exc.code}): {content}") from exc
            raise JavaApiError(f"Java API服务错误({exc.code}): {content}") from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise JavaApiError(f"无法访问Java API: {exc}") from exc
        if payload.get("code") != "OK" or "data" not in payload:
            raise JavaApiError(f"Java API响应结构异常: {payload}")
        return payload
