"""GRPO-v1独立推理入口的兼容性和可复现性测试。"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from eval.evaluate_grpo import evaluate_grpo, validate_model_compatibility


class ModelCompatibilityTest(unittest.TestCase):
    """验证第一版模型只接受已确认的3设备RSMA结构。"""

    def test_accepts_three_device_rsma_scene(self) -> None:
        validate_model_compatibility(
            env_id="RSMA_ENV",
            access_scheme="RSMA",
            device_count=3,
        )

    def test_rejects_incompatible_scene_structure(self) -> None:
        invalid_scenes = [
            {"env_id": "RSMA_ENV", "access_scheme": "RSMA", "device_count": 4},
            {"env_id": "RSMA_ENV", "access_scheme": "NOMA", "device_count": 3},
            {"env_id": "OTHER_ENV", "access_scheme": "RSMA", "device_count": 3},
        ]
        for scene in invalid_scenes:
            with self.subTest(scene=scene):
                with self.assertRaises(ValueError):
                    validate_model_compatibility(**scene)


class ReproducibleInferenceTest(unittest.TestCase):
    """使用真实可信权重验证相同场景和种子产生相同业务指标。"""

    def test_same_seed_produces_same_throughput_metrics(self) -> None:
        checkpoint = os.getenv("GRPO_TEST_CHECKPOINT")
        if not checkpoint:
            self.skipTest("GRPO_TEST_CHECKPOINT is not configured")

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            first = evaluate_grpo(
                checkpoint,
                num_episodes=2,
                max_ep_len=10,
                deterministic=True,
                env_id="RSMA_ENV",
                seed=2026,
                output_dir=str(root / "first"),
            )
            second = evaluate_grpo(
                checkpoint,
                num_episodes=2,
                max_ep_len=10,
                deterministic=True,
                env_id="RSMA_ENV",
                seed=2026,
                output_dir=str(root / "second"),
            )

            metric_names = (
                "throughputMean",
                "throughputStd",
                "throughputMin",
                "throughputMax",
                "totalTimesteps",
            )
            for metric_name in metric_names:
                self.assertEqual(
                    first["metrics"][metric_name],
                    second["metrics"][metric_name],
                )
            self.assertIsNone(first["metrics"]["averageAoi"])
            self.assertFalse(first["evaluation"]["trainingPerformed"])


if __name__ == "__main__":
    unittest.main()
