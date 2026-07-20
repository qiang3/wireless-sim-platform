import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from worker.contract import ContractError, completion_body, evaluation_kwargs, parse_execution_message


class WorkerContractTest(unittest.TestCase):
    def setUp(self):
        self.claim = {
            "executionId": 7,
            "modelId": "grpo-rsma-throughput-v1",
            "scene": {
                "accessScheme": "RSMA", "deviceCount": 3, "antennaCount": 1,
                "timeSlotCount": 100, "dataArrivalRateMegabitPerSlot": 3,
                "averageGreenEnergyMilliJoule": 6, "batteryCapacityMilliJoule": 12,
                "dataBufferCapacityMegabit": 3, "wptTransmitPowerWatt": 4,
                "deviceMaxTransmitPowerMilliWatt": 100,
            },
            "evaluation": {"numEpisodes": 10, "maxEpisodeLength": 100,
                           "deterministic": True, "baseSeed": 2026},
        }

    def test_java_scene_units_map_to_environment_names(self):
        kwargs = evaluation_kwargs(self.claim, "model.pt", "out")
        self.assertEqual(4.0, kwargs["P_wpt"])
        self.assertEqual(100.0, kwargs["P_max"])
        self.assertEqual(12.0, kwargs["B_max"])
        self.assertEqual(3.0, kwargs["lambda_arrival"])

    def test_completion_rejects_fake_aoi(self):
        result = {"model": {"modelId": self.claim["modelId"], "checkpointSha256": "a" * 64},
                  "evaluation": {"baseSeed": 2026},
                  "metrics": {"averageAoi": 1}}
        with self.assertRaises(ContractError):
            completion_body(self.claim, result)

    def test_message_schema_is_versioned(self):
        body = (b'{"eventId":"e","taskId":1,"attemptNo":1,'
                b'"eventType":"TASK_EXECUTION_REQUESTED","schemaVersion":1,'
                b'"occurredAt":"2026-07-20T00:00:00Z"}')
        self.assertEqual(1, parse_execution_message(body)["taskId"])


if __name__ == "__main__":
    unittest.main()
