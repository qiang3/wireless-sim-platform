"""
GRPO trained-model evaluation script.

Features
--------
1. Load a trained checkpoint such as latest.pt from a specified path.
2. Recreate the training environment, preferentially using environment arguments
   stored in checkpoint["meta"]["kwargs"].
3. Run evaluation only: no gradient calculation and no network update.
4. Evaluate 100 episodes by default.
5. Log evaluation metrics with the same main tag names used during training:
       current_ep_reward/timestep
       total_reward/iter
       reward/avg_reward_per_10_eps
       iter_time_s/iter
       elapsed_time_s/iter
       throughput/elapsed_time_s
   Network-update metrics such as policy_loss, entropy, kl_ref and log_std are
   intentionally not recorded.
6. Export TensorBoard data to CSV and generate plots through the existing
   TBLogger, exactly like the training entry point.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Mapping, MutableMapping, Optional, Tuple

import numpy as np
import torch

# 无论从仓库根目录还是python-worker目录启动，都能导入同级algo/env/utils包。
WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from algo.grpo import GRPO
from utils.env_utils import create_env
from utils.tb_logger import TBLogger


# 第一版只接入已经验证过的3设备RSMA吞吐量模型。
MODEL_ID = "grpo-rsma-throughput-v1"
MODEL_ALGORITHM = "GRPO"
SUPPORTED_ENVIRONMENT = "RSMA_ENV"
SUPPORTED_ACCESS_SCHEME = "RSMA"
SUPPORTED_DEVICE_COUNT = 3
SUPPORTED_OBSERVATION_DIMENSION = 15
SUPPORTED_ACTION_DIMENSION = 13


# These arguments control training rather than environment construction.
# They must not be forwarded to create_env when recovered from the checkpoint.
_TRAIN_ONLY_KEYS = {
    "resume_from",
    "log_every",
    "max_iter",
    "render_every_iter",
    "group_size",
    "updates_per_iter",
    "max_ep_len",
    "temperature",
    "gamma",
}


def _safe_torch_load(path: Path, map_location: torch.device) -> Mapping[str, Any]:
    """Load a checkpoint and provide a clear error for an invalid file."""
    if not path.is_file():
        raise FileNotFoundError(f"Checkpoint does not exist: {path}")

    try:
        try:
            # 权重只允许来自预先登记的可信文件；优先使用受限反序列化模式。
            payload = torch.load(
                str(path), map_location=map_location, weights_only=True
            )
        except TypeError:
            # 兼容尚不支持weights_only参数的旧版PyTorch。
            payload = torch.load(str(path), map_location=map_location)
    except Exception as exc:
        raise RuntimeError(f"Failed to load checkpoint: {path}") from exc

    if not isinstance(payload, Mapping):
        raise TypeError(
            "Unsupported checkpoint format: expected a dict-like object, "
            f"but got {type(payload).__name__}."
        )
    return payload


def _checkpoint_env_config(
    payload: Mapping[str, Any],
    env_id_override: Optional[str],
    env_overrides: Mapping[str, Any],
) -> Tuple[str, Dict[str, Any]]:
    """Recover environment configuration from checkpoint metadata."""
    meta = payload.get("meta", {})
    meta = meta if isinstance(meta, Mapping) else {}

    saved_kwargs = meta.get("kwargs", {})
    saved_kwargs = dict(saved_kwargs) if isinstance(saved_kwargs, Mapping) else {}

    saved_env_id = saved_kwargs.pop("env_id", None)
    env_id = env_id_override or saved_env_id or "RSMA_ENV"

    for key in _TRAIN_ONLY_KEYS:
        saved_kwargs.pop(key, None)

    # Explicit evaluation arguments take precedence over checkpoint metadata.
    saved_kwargs.update(dict(env_overrides))
    return str(env_id), saved_kwargs


def _extract_agent_checkpoint(payload: Mapping[str, Any]) -> Mapping[str, Any]:
    """Support the project's full checkpoint and several simpler state formats."""
    agent_state = payload.get("agent", payload)
    if not isinstance(agent_state, Mapping):
        raise TypeError("checkpoint['agent'] must be a dict-like object.")
    return agent_state


def _load_policy_for_inference(
    agent: GRPO,
    payload: Mapping[str, Any],
    device: torch.device,
) -> None:
    """
    Load policy weights only.

    The optimizer is intentionally not restored because evaluation never updates
    parameters. policy_old and policy_ref are synchronized only for consistency.
    """
    agent_state = _extract_agent_checkpoint(payload)

    if "policy" in agent_state:
        policy_state = agent_state["policy"]
    elif "policy_old" in agent_state:
        policy_state = agent_state["policy_old"]
    else:
        # Also permit a direct ActorOnly state_dict.
        policy_state = agent_state

    try:
        agent.policy.load_state_dict(policy_state, strict=True)
    except Exception as exc:
        raise RuntimeError(
            "Policy weights do not match the environment dimensions. Check that "
            "the evaluation environment parameters are identical to training."
        ) from exc

    agent.policy_old.load_state_dict(agent.policy.state_dict())
    agent.policy_ref.load_state_dict(agent.policy.state_dict())

    # Prefer the exact action transform saved at training time when available.
    if "action_scale" in agent_state:
        agent.action_scale = torch.as_tensor(
            agent_state["action_scale"], dtype=torch.float32, device=device
        )
    if "action_bias" in agent_state:
        agent.action_bias = torch.as_tensor(
            agent_state["action_bias"], dtype=torch.float32, device=device
        )

    for module in (agent.policy, agent.policy_old, agent.policy_ref):
        module.eval()
        for parameter in module.parameters():
            parameter.requires_grad_(False)


@torch.inference_mode()
def _select_eval_action(
    agent: GRPO,
    observation: np.ndarray,
    device: torch.device,
    deterministic: bool,
) -> np.ndarray:
    """Generate an environment-space action using the training-time transform."""
    state = torch.as_tensor(
        observation, dtype=torch.float32, device=device
    ).unsqueeze(0)

    if deterministic:
        # Deterministic validation: use the Gaussian policy mean.
        raw_action = agent.policy.actor(state)
    else:
        # Stochastic validation: sample from the trained Gaussian policy.
        raw_action, _ = agent.policy.act(state)

    squashed = torch.tanh(raw_action)
    squashed = torch.clamp(squashed, -1.0 + 1e-6, 1.0 - 1e-6)
    action = squashed * agent.action_scale + agent.action_bias
    return action.squeeze(0).cpu().numpy()


def _reset_env(env: Any, seed: Optional[int]) -> np.ndarray:
    """Handle both old and new Gym reset APIs."""
    try:
        result = env.reset(seed=seed) if seed is not None else env.reset()
    except TypeError:
        if seed is not None and hasattr(env, "seed"):
            env.seed(seed)
        result = env.reset()

    observation = result[0] if isinstance(result, tuple) else result
    return np.asarray(observation, dtype=np.float32)


def _step_env(env: Any, action: np.ndarray) -> Tuple[np.ndarray, float, bool, Dict[str, Any]]:
    """Handle both four-value and five-value Gym step APIs."""
    result = env.step(action)
    if not isinstance(result, tuple):
        raise TypeError("env.step(action) must return a tuple.")

    if len(result) == 5:
        next_obs, reward, terminated, truncated, info = result
        done = bool(terminated or truncated)
    elif len(result) == 4:
        next_obs, reward, done, info = result
        done = bool(done)
    else:
        raise ValueError(f"Unsupported env.step return length: {len(result)}")

    info = info if isinstance(info, dict) else {}
    return np.asarray(next_obs, dtype=np.float32), float(reward), done, info


def _append_scalar_info(
    accumulator: MutableMapping[str, list],
    info: Mapping[str, Any],
) -> None:
    """Collect scalar environment diagnostics without assuming specific key names."""
    for key, value in info.items():
        if isinstance(value, (bool, int, float, np.number)):
            number = float(value)
            if np.isfinite(number):
                accumulator[str(key)].append(number)


def validate_model_compatibility(
    *,
    env_id: str,
    access_scheme: str,
    device_count: int,
) -> None:
    """在加载权重前拒绝当前GRPO-v1无法处理的场景结构。"""
    if env_id.upper() != SUPPORTED_ENVIRONMENT:
        raise ValueError(
            f"GRPO-v1 only supports env_id={SUPPORTED_ENVIRONMENT}, got {env_id}."
        )
    if access_scheme.upper() != SUPPORTED_ACCESS_SCHEME:
        raise ValueError(
            "GRPO-v1 only supports the RSMA access scheme, "
            f"got {access_scheme}."
        )
    if device_count != SUPPORTED_DEVICE_COUNT:
        raise ValueError(
            "GRPO-v1 was trained with exactly 3 devices "
            f"(observation=15, action=13), got {device_count}."
        )


def _validate_scene_values(
    *, antenna_count: Optional[int], env_overrides: Mapping[str, Any]
) -> None:
    """校验CLI传入的物理量，不改变RsmaEnv中任何原始默认参数。"""
    if antenna_count is not None and antenna_count <= 0:
        raise ValueError("antenna_count must be greater than zero when provided.")
    strictly_positive = ("N", "B_max", "Q_max", "P_wpt", "P_max")
    for key in strictly_positive:
        value = env_overrides.get(key)
        if value is not None and float(value) <= 0:
            raise ValueError(f"{key} must be greater than zero.")
    non_negative = ("lambda_arrival", "E_mean")
    for key in non_negative:
        value = env_overrides.get(key)
        if value is not None and float(value) < 0:
            raise ValueError(f"{key} must not be negative.")


def _checkpoint_sha256(path: Path) -> str:
    """计算可信本地权重的SHA-256，便于结果追溯到准确模型文件。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path: Path, payload: Mapping[str, Any]) -> None:
    """先写临时文件再原子替换，避免进程中断留下半个JSON结果。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    with temporary_path.open("w", encoding="utf-8") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary_path.replace(path)


def evaluate_grpo(
    checkpoint_path: str,
    *,
    num_episodes: int = 100,
    max_ep_len: int = 1000,
    deterministic: bool = True,
    env_id: Optional[str] = None,
    seed: Optional[int] = 2026,
    render: bool = False,
    output_dir: Optional[str] = None,
    result_json_path: Optional[str] = None,
    access_scheme: str = SUPPORTED_ACCESS_SCHEME,
    device_count: int = SUPPORTED_DEVICE_COUNT,
    antenna_count: Optional[int] = None,
    **env_overrides: Any,
) -> Dict[str, Any]:
    """
    Evaluate a trained GRPO model.

    Parameters
    ----------
    checkpoint_path:
        Target latest.pt path.
    num_episodes:
        Number of validation episodes; default is 100.
    max_ep_len:
        Maximum number of environment steps in each episode.
    deterministic:
        True uses the policy mean; False samples from the learned Gaussian policy.
    env_id:
        Optional environment ID override. Otherwise use checkpoint metadata.
    seed:
        Base validation seed. Episode k uses seed + k. Set to None for no manual seed.
    render:
        Whether to call env.render() after every step.
    output_dir:
        Optional TensorBoard/CSV output directory. If omitted, TBLogger creates one.
    env_overrides:
        Environment parameters that override checkpoint metadata.
    """
    if num_episodes <= 0:
        raise ValueError("num_episodes must be greater than zero.")
    if max_ep_len <= 0:
        raise ValueError("max_ep_len must be greater than zero.")

    effective_env_id = env_id or SUPPORTED_ENVIRONMENT
    validate_model_compatibility(
        env_id=effective_env_id,
        access_scheme=access_scheme,
        device_count=device_count,
    )
    # 显式传入与训练时相同的3设备默认值；其他未传参数继续使用RsmaEnv原有DEFAULTS。
    env_overrides = dict(env_overrides)
    env_overrides["num_devices"] = device_count
    _validate_scene_values(
        antenna_count=antenna_count,
        env_overrides=env_overrides,
    )

    print("\n========== Evaluate GRPO ==========")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    checkpoint = Path(checkpoint_path).expanduser().resolve()
    payload = _safe_torch_load(checkpoint, map_location=device)

    eval_env_id, env_kwargs = _checkpoint_env_config(
        payload, env_id_override=effective_env_id, env_overrides=env_overrides
    )
    print(f"[EVAL] checkpoint   : {checkpoint}")
    print(f"[EVAL] device       : {device}")
    print(f"[EVAL] environment  : {eval_env_id}")
    print(f"[EVAL] episodes     : {num_episodes}")
    print(f"[EVAL] deterministic: {deterministic}")

    if seed is not None:
        random.seed(seed)
        np.random.seed(seed)
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)

    env = create_env(eval_env_id, **env_kwargs)
    try:
        obs_dim = int(env.observation_space.shape[0])
        act_dim = int(env.action_space.shape[0])
        if obs_dim != SUPPORTED_OBSERVATION_DIMENSION or act_dim != SUPPORTED_ACTION_DIMENSION:
            raise ValueError(
                "GRPO-v1 environment dimensions are incompatible with the trained policy: "
                f"expected observation={SUPPORTED_OBSERVATION_DIMENSION}, "
                f"action={SUPPORTED_ACTION_DIMENSION}; got observation={obs_dim}, action={act_dim}."
            )

        agent = GRPO(
            obs_dim,
            act_dim,
            lr_actor=3e-4,
            action_std_init=0.8,
            action_low=env.action_space.low,
            action_high=env.action_space.high,
            # The following values do not affect inference, but keep construction
            # consistent with the training class.
            eps_clip=0.2,
            entropy_coef=0.01,
            kl_coef=0.04,
            minibatch_size=64,
            K_epochs=4,
            max_grad_norm=0.5,
            refresh_ref_every=100,
            gamma=0.99,
        )
        _load_policy_for_inference(agent, payload, device)

        logger_kwargs = dict(env_kwargs)
        logger_kwargs.update(
            {
                "env_id": eval_env_id,
                "num_episodes": num_episodes,
                "deterministic_eval": deterministic,
            }
        )
        if output_dir is not None:
            log_dir = Path(output_dir).expanduser().resolve()
            log_dir.mkdir(parents=True, exist_ok=True)
            tb = TBLogger("GRPO_EVAL", resume_log_dir=str(log_dir))
        else:
            tb = TBLogger("GRPO_EVAL", **logger_kwargs)

        eval_t0 = time.perf_counter()
        total_reward = 0.0
        total_timestep = 0
        ten_episode_reward = 0.0
        throughputs = []
        episode_lengths = []
        episode_times = []

        for episode_idx in range(1, num_episodes + 1):
            episode_t0 = time.perf_counter()
            episode_seed = None if seed is None else seed + episode_idx - 1
            observation = _reset_env(env, episode_seed)

            episode_reward = 0.0
            episode_info_values: MutableMapping[str, list] = defaultdict(list)
            steps = 0

            for _ in range(max_ep_len):
                action = _select_eval_action(
                    agent,
                    observation,
                    device=device,
                    deterministic=deterministic,
                )
                observation, reward, done, info = _step_env(env, action)

                episode_reward += reward
                steps += 1
                total_timestep += 1
                _append_scalar_info(episode_info_values, info)

                if render:
                    env.render()
                if done:
                    break

            episode_time = time.perf_counter() - episode_t0
            elapsed_time = time.perf_counter() - eval_t0

            total_reward += episode_reward
            ten_episode_reward += episode_reward
            throughputs.append(episode_reward)
            episode_lengths.append(steps)
            episode_times.append(episode_time)

            # Keep the same core metric names and x-axis meanings as training.
            tb.log_scalar(
                "current_ep_reward/timestep",
                episode_reward,
                total_timestep,
            )
            tb.log_scalar("total_reward/iter", total_reward, episode_idx)
            tb.log_scalar("iter_time_s/iter", episode_time, episode_idx)
            tb.log_scalar("elapsed_time_s/iter", elapsed_time, episode_idx)
            tb.log_scalar(
                "throughput/elapsed_time_s",
                episode_reward,
                elapsed_time,
            )

            # Evaluation-only environment metrics; these are not network-update metrics.
            tb.log_scalar("episode_length/iter", steps, episode_idx)
            tb.log_scalar(
                "throughput_per_step/iter",
                episode_reward / max(steps, 1),
                episode_idx,
            )
            for key, values in episode_info_values.items():
                if values:
                    tb.log_scalar(
                        f"env_info/{key}/iter",
                        float(np.mean(values)),
                        episode_idx,
                    )

            if episode_idx % 10 == 0:
                avg_10 = ten_episode_reward / 10.0
                tb.log_scalar(
                    "reward/avg_reward_per_10_eps",
                    avg_10,
                    episode_idx // 10,
                )
                ten_episode_reward = 0.0

            print(
                "GRPO-EVAL | "
                f"Episode={episode_idx:03d}/{num_episodes} | "
                f"Timestep={total_timestep} | "
                f"Throughput={episode_reward:.6f} | "
                f"Steps={steps} | "
                f"EpisodeTime={episode_time:.4f}s | "
                f"Elapsed={elapsed_time:.4f}s"
            )

        total_eval_time = time.perf_counter() - eval_t0
        throughput_array = np.asarray(throughputs, dtype=np.float64)
        length_array = np.asarray(episode_lengths, dtype=np.float64)
        time_array = np.asarray(episode_times, dtype=np.float64)

        metric_summary = {
            "num_episodes": float(num_episodes),
            "total_timesteps": float(total_timestep),
            "throughput_mean": float(throughput_array.mean()),
            "throughput_std": float(throughput_array.std(ddof=0)),
            "throughput_min": float(throughput_array.min()),
            "throughput_max": float(throughput_array.max()),
            "episode_length_mean": float(length_array.mean()),
            "episode_time_mean_s": float(time_array.mean()),
            "total_eval_time_s": float(total_eval_time),
        }

        # Summary values use one fixed step and are separated from per-episode curves.
        for key, value in metric_summary.items():
            tb.log_scalar(f"eval_summary/{key}", value, 1)

        tb.flush()
        tb.close()
        tb.save_tb_to_csv()
        tb.plot_all()

        print("\n========== Evaluation Summary ==========")
        print(f"Mean throughput : {metric_summary['throughput_mean']:.6f}")
        print(f"Std throughput  : {metric_summary['throughput_std']:.6f}")
        print(f"Min / Max       : {metric_summary['throughput_min']:.6f} / {metric_summary['throughput_max']:.6f}")
        print(f"Mean steps      : {metric_summary['episode_length_mean']:.2f}")
        print(f"Mean ep. time   : {metric_summary['episode_time_mean_s']:.6f} s")
        print(f"Total eval time : {metric_summary['total_eval_time_s']:.6f} s")
        print(f"Log directory   : {tb.log_dir}")

        artifact_directory = str(Path(tb.log_dir).resolve())
        standard_result: Dict[str, Any] = {
            "schemaVersion": 1,
            "model": {
                "modelId": MODEL_ID,
                "algorithm": MODEL_ALGORITHM,
                "checkpointSha256": _checkpoint_sha256(checkpoint),
                "environmentId": eval_env_id,
                "observationDimension": obs_dim,
                "actionDimension": act_dim,
            },
            "evaluation": {
                "mode": "PRETRAINED_MODEL",
                "trainingPerformed": False,
                "deterministic": deterministic,
                "baseSeed": seed,
                "numEpisodes": num_episodes,
                "maxEpisodeLength": max_ep_len,
            },
            "scenario": {
                "accessScheme": SUPPORTED_ACCESS_SCHEME,
                "deviceCount": int(env.num_devices),
                "antennaCount": antenna_count,
                "antennaCountUsedByModel": False,
                "timeSlotCount": int(env.N),
                "dataArrivalRateMbpsPerSlot": float(env.lambda_arrival),
                "averageGreenEnergyMilliJoule": float(env.E_mean),
                "batteryCapacityMilliJoule": float(env.B_max),
                "dataBufferCapacityMegabit": float(env.Q_max),
                "wptTransmitPowerWatt": float(env.P_wpt),
                "deviceMaxTransmitPowerMilliWatt": float(env.P_max),
            },
            "metrics": {
                "throughputUnit": "Mbit/episode",
                "throughputMean": metric_summary["throughput_mean"],
                "throughputStd": metric_summary["throughput_std"],
                "throughputMin": metric_summary["throughput_min"],
                "throughputMax": metric_summary["throughput_max"],
                "averageAoi": None,
                "totalTimesteps": int(metric_summary["total_timesteps"]),
                "episodeLengthMean": metric_summary["episode_length_mean"],
                "episodeTimeMeanSeconds": metric_summary["episode_time_mean_s"],
                "totalEvaluationTimeSeconds": metric_summary["total_eval_time_s"],
            },
            "artifacts": {
                "directory": artifact_directory,
            },
        }
        result_path = (
            Path(result_json_path).expanduser().resolve()
            if result_json_path is not None
            else Path(artifact_directory) / "summary.json"
        )
        _write_json(result_path, standard_result)
        standard_result["artifacts"]["summaryJson"] = str(result_path)
        # 将包含summaryJson自身路径的最终结构再写一次，保证文件和返回值完全一致。
        _write_json(result_path, standard_result)
        print(f"Summary JSON    : {result_path}")
        return standard_result

    finally:
        env.close()


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Load latest.pt and evaluate a trained GRPO policy."
    )
    parser.add_argument(
        "--checkpoint",
        default=os.getenv("GRPO_CHECKPOINT_PATH"),
        help="Path to the trained latest.pt checkpoint.",
    )
    # 保留原main()写死的评估默认值：10回合、每回合最多100步、RSMA环境。
    parser.add_argument("--episodes", type=int, default=10)
    parser.add_argument("--max_ep_len", type=int, default=None)
    parser.add_argument("--env_id", default=SUPPORTED_ENVIRONMENT)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--output_dir", default=os.getenv("GRPO_OUTPUT_DIR"))
    parser.add_argument("--result_json", default=None)
    parser.add_argument("--access_scheme", default=SUPPORTED_ACCESS_SCHEME)
    parser.add_argument("--device_count", type=int, default=SUPPORTED_DEVICE_COUNT)
    parser.add_argument("--antenna_count", type=int, default=None)
    parser.add_argument("--time_slot_count", type=int, default=None)
    parser.add_argument("--data_arrival_rate", type=float, default=None)
    parser.add_argument("--average_green_energy", type=float, default=None)
    parser.add_argument("--battery_capacity", type=float, default=None)
    parser.add_argument("--data_buffer_capacity", type=float, default=None)
    parser.add_argument("--wpt_transmit_power", type=float, default=None)
    parser.add_argument("--device_max_transmit_power", type=float, default=None)
    parser.add_argument(
        "--stochastic",
        action="store_true",
        help="Sample actions from the policy instead of using the policy mean.",
    )
    parser.add_argument("--render", action="store_true")
    return parser


def main() -> None:
    args = _build_parser().parse_args()
    if not args.checkpoint:
        raise SystemExit("--checkpoint or GRPO_CHECKPOINT_PATH is required.")

    env_overrides = {
        key: value
        for key, value in {
            "N": args.time_slot_count,
            "lambda_arrival": args.data_arrival_rate,
            "E_mean": args.average_green_energy,
            "B_max": args.battery_capacity,
            "Q_max": args.data_buffer_capacity,
            "P_wpt": args.wpt_transmit_power,
            "P_max": args.device_max_transmit_power,
        }.items()
        if value is not None
    }
    # 未传time_slot_count和max_ep_len时仍使用原值100；传入时隙数后默认评估完整时隙。
    max_ep_len = (
        args.max_ep_len
        if args.max_ep_len is not None
        else args.time_slot_count
        if args.time_slot_count is not None
        else 100
    )
    evaluate_grpo(
        checkpoint_path=args.checkpoint,
        env_id=args.env_id,
        num_episodes=args.episodes,
        max_ep_len=max_ep_len,
        deterministic=not args.stochastic,
        seed=args.seed,
        render=args.render,
        output_dir=args.output_dir,
        result_json_path=args.result_json,
        access_scheme=args.access_scheme,
        device_count=args.device_count,
        antenna_count=args.antenna_count,
        **env_overrides,
    )


if __name__ == "__main__":
    main()
