import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from worker.java_api import JavaApiError
from worker.main import RabbitGrpoWorker, WorkerConfig


class FakeChannel:
    def __init__(self):
        self.acks = []
        self.publishes = []

    def basic_ack(self, delivery_tag):
        self.acks.append(delivery_tag)

    def basic_publish(self, **kwargs):
        self.publishes.append(kwargs)
        return True


class RabbitWorkerFlowTest(unittest.TestCase):
    def setUp(self):
        self.message = {
            "eventId": "event-1", "taskId": 10, "attemptNo": 1,
            "eventType": "TASK_EXECUTION_REQUESTED", "schemaVersion": 1,
            "occurredAt": "2026-07-20T00:00:00Z",
        }
        self.properties = SimpleNamespace(
            headers={"attemptNo": 1, "schemaVersion": 1},
            message_id="event-1", type="TASK_EXECUTION_REQUESTED",
            content_type="application/json", timestamp=None,
        )
        self.method = SimpleNamespace(delivery_tag=99)
        self.temp = tempfile.TemporaryDirectory()
        self.config = SimpleNamespace(
            worker_id="python-test", checkpoint="model.pt",
            output_root=Path(self.temp.name), max_attempts=3,
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_successful_inference_callbacks_before_ack(self):
        claim = {
            "outcome": "CLAIMED", "executionId": 3,
            "modelId": "grpo-rsma-throughput-v1",
            "scene": {"accessScheme": "RSMA", "deviceCount": 3, "antennaCount": 1,
                      "timeSlotCount": 10, "dataArrivalRateMegabitPerSlot": 3,
                      "averageGreenEnergyMilliJoule": 6, "batteryCapacityMilliJoule": 12,
                      "dataBufferCapacityMegabit": 3, "wptTransmitPowerWatt": 4,
                      "deviceMaxTransmitPowerMilliWatt": 100},
            "evaluation": {"numEpisodes": 2, "maxEpisodeLength": 10,
                           "deterministic": True, "baseSeed": 2026},
        }

        class Api:
            completed = None
            def claim(self, *_): return claim
            def complete(self, task_id, attempt_no, body):
                self.completed = (task_id, attempt_no, body)

        result = {
            "model": {"modelId": "grpo-rsma-throughput-v1", "checkpointSha256": "a" * 64},
            "evaluation": {"baseSeed": 2026},
            "metrics": {"throughputMean": 39.3, "throughputStd": 1.2,
                        "throughputMin": 38.1, "throughputMax": 40.5,
                        "averageAoi": None, "totalTimesteps": 20,
                        "totalEvaluationTimeSeconds": 0.2},
            "artifacts": {"directory": self.temp.name},
        }
        api, channel = Api(), FakeChannel()
        with patch("worker.main.evaluate_grpo", return_value=result):
            RabbitGrpoWorker(self.config, api).on_message(
                channel, self.method, self.properties, json.dumps(self.message).encode()
            )
        self.assertEqual([99], channel.acks)
        self.assertEqual(10, api.completed[0])
        self.assertEqual(39.3, api.completed[2]["throughputMean"])

    def test_java_network_error_is_forwarded_to_retry_before_ack(self):
        class Api:
            def claim(self, *_): raise JavaApiError("temporary")

        channel = FakeChannel()
        RabbitGrpoWorker(self.config, Api()).on_message(
            channel, self.method, self.properties, json.dumps(self.message).encode()
        )
        self.assertEqual([99], channel.acks)
        self.assertEqual("simulation.task.retry.exchange", channel.publishes[0]["exchange"])
        self.assertEqual(2, channel.publishes[0]["properties"].headers["x-delivery-attempt"])

    def test_worker_config_reads_positive_rabbitmq_heartbeat(self):
        """断点调试可覆盖心跳，生产默认值仍留在代码中而不是永久改大。"""
        with patch.dict(os.environ, {
            "SIMULATION_WORKER_TOKEN": "test-token",
            "GRPO_CHECKPOINT_PATH": "model.pt",
            "RABBITMQ_HEARTBEAT_SECONDS": "300",
        }, clear=False):
            config = WorkerConfig()
        self.assertEqual(300, config.rabbit_heartbeat_seconds)

    def test_worker_config_rejects_disabled_heartbeat(self):
        with patch.dict(os.environ, {
            "SIMULATION_WORKER_TOKEN": "test-token",
            "GRPO_CHECKPOINT_PATH": "model.pt",
            "RABBITMQ_HEARTBEAT_SECONDS": "0",
        }, clear=False):
            with self.assertRaisesRegex(RuntimeError, "必须大于0"):
                WorkerConfig()


if __name__ == "__main__":
    unittest.main()
