import os
import numpy as np
import torch
from pathlib import Path
from utils.env_utils import create_env
from utils.tb_logger import TBLogger
from algo.grpo import GRPO
import time


def _obs_from_env(env):
    # set_state() 后，需要手动拼当前观测
    return np.concatenate([env.E_a, env.B, env.Q, env.G1, env.G2]).astype(np.float32)


def train_grpo(**kwargs):
    """
    主要可选参数（与 PPO / DSAC 风格对齐）：
        resume_from: str | None       # e.g. ".../runs/GRPO/<param_subdir>/checkpoints/latest.pt"
        max_iter: int = 10000         # 默认可被覆盖

    说明：
        - 不再按步数保存中间模型，仅在训练结束时保存一次 latest.pt。
        - 其它环境 / TB 参数仍按原来传入。
    """
    print("\n========== Train GRPO ==========")
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

    # === 保存 / 恢复相关参数 ===
    resume_from = kwargs.pop("resume_from", None)
    log_every = int(kwargs.pop("log_every", 1000))
    max_iter = int(kwargs.get("max_iter", 10000))
    env_id = str(kwargs.get("env_id", "RSMA_env"))
    render_every_iter = int(kwargs.pop("render_every_iter", 1000))

    # 组采样超参
    group_size = int(kwargs.pop("group_size", 8))        # 建议 6~12
    updates_per_iter = int(kwargs.pop("updates_per_iter", 1))
    max_ep_len = int(kwargs.pop("max_ep_len", 1000))
    temperature = float(kwargs.pop("temperature", 0.5))
    gamma = float(kwargs.pop("gamma", 0.99))

    # ====== 环境 & 维度 ======
    env = create_env(env_id, **kwargs)
    obs_dim = env.observation_space.shape[0]
    act_dim = env.action_space.shape[0]
    action_low = env.action_space.low
    action_high = env.action_space.high

    # === TensorBoard：新开或复用 ===
    if resume_from is not None:
        ckpt_raw = torch.load(resume_from, map_location="cpu")
        old_log_dir = ckpt_raw.get("meta", {}).get("tb_log_dir", None)
        if old_log_dir is None:
            # 若 meta 里没记，则取 .../runs/GRPO/<param_subdir>
            p = Path(resume_from).resolve()
            old_log_dir = str(p.parent.parent)
        tb = TBLogger("GRPO", resume_log_dir=old_log_dir)
    else:
        tb = TBLogger("GRPO", **kwargs)

    ckpt_dir = Path(tb.log_dir) / "checkpoints"
    ckpt_dir.mkdir(parents=True, exist_ok=True)

    # ====== GRPO Agent ======
    agent = GRPO(
        obs_dim, act_dim,
        lr_actor=3e-4,
        action_std_init=0.8,
        action_low=action_low, action_high=action_high,
        eps_clip=0.2, entropy_coef=0.01, kl_coef=0.04,
        minibatch_size=64, K_epochs=4, max_grad_norm=0.5,
        refresh_ref_every=100,
        gamma=gamma,                                      # 折扣传入
    )

    # 计数器
    timestep, updates, iter_idx = 0, 0, 0
    total_reward, episode_reward = 0.0, 0.0
    episode_len, episode_idx = 10, 0

    # === 断点恢复（加载权重 + 接续计数器 + TB 步数对齐）===
    if resume_from is not None:
        meta = _load_checkpoint(resume_from, agent, map_location=device)
        ckpt_step = int(meta.get("timestep", 0))
        ckpt_updates = int(meta.get("updates", 0))
        ckpt_iter = int(meta.get("iter_idx", 0))
        ckpt_episode_idx = int(meta.get("episode_idx", 0))
        ckpt_total_reward = float(meta.get("total_reward", 0.0))

        last_tb_step = tb.max_step()  # -1 表示空
        start_step = max(ckpt_step, last_tb_step if last_tb_step >= 0 else 0)

        timestep = start_step
        updates = ckpt_updates
        iter_idx = ckpt_iter
        episode_idx = ckpt_episode_idx
        total_reward = ckpt_total_reward
        episode_reward = 0.0  # 滑动窗口从本轮重新开始

        print(f"[RESUME][GRPO] from={resume_from}")
        print(f"[RESUME][GRPO] tb_dir={tb.log_dir}")
        print(f"[RESUME][GRPO] last_tb_step={last_tb_step}, ckpt_step={ckpt_step} => start timestep={timestep}")

    # st = time.time()
    train_t0 = time.perf_counter()  # 记录训练开始时间
    while iter_idx < max_iter:
        iter_t0 = time.perf_counter()  # 记录当前迭代开始时间
        # ===== 同一状态分组采样 =====
        state0, _ = env.reset()
        base = env.get_state()          # 保存完整环境 + RNG 状态
        traj_group = []
        is_render = ((iter_idx + 1) % render_every_iter == 0)  # 每隔若干回合渲染一次

        for g in range(group_size):
            env.set_state(base)         # 回到完全相同的起点与随机数轨迹
            s = _obs_from_env(env)      # 重建观测
            traj = dict(states=[], actions_raw=[], old_logprobs=[],
                        step_rewards=[], R=0.0)
            ep_ret = 0.0

            for t in range(1, max_ep_len + 1):
                a_env, a_raw, oldlp, s_t = agent.select_action(s)
                ns, r, done, info = env.step(a_env)
                # if is_render and g == 0: todo 暂时关闭渲染
                #     env.render()
                #     print("采取的动作：", a_env)
                #     print("奖励：", r)
                #     print("=====" * 8)

                # 记录 rollout
                traj["states"].append(s_t)
                traj["actions_raw"].append(a_raw)
                traj["old_logprobs"].append(oldlp)
                traj["step_rewards"].append(float(r))
                ep_ret += float(r)

                # 统计：只在组内第一个采样器更新全局步数
                if g == 0:
                    timestep += 1

                s = ns.astype(np.float32)
                if done:
                    break

            traj["R"] = ep_ret
            traj_group.append(traj)

        # ===== 更新 =====
        for _ in range(updates_per_iter):
            stats = agent.update_group(traj_group, temperature=temperature)
            updates += 1
            if timestep > 0 and timestep % log_every == 0:
                for k, v in stats.items():
                    tb.log_scalar(k, v, timestep)

        current_ep_reward = float(np.mean([tb_["R"] for tb_ in traj_group]))
        total_reward += current_ep_reward
        episode_reward += current_ep_reward
        print(f"GRPO | Timestep={timestep} | GroupMeanReturn={current_ep_reward:.4f}")
        tb.log_scalar("current_ep_reward/timestep", current_ep_reward, timestep)

        iter_idx += 1
        tb.log_scalar("total_reward/iter", total_reward, iter_idx)
        if iter_idx % episode_len == 0:
            episode_idx += 1
            tb.log_scalar(f"reward/avg_reward_per_{episode_len:02d}_eps",
                          episode_reward / episode_len, episode_idx)
            episode_reward = 0.0

        # todo 添加时间记录:每轮用时多少秒
        tb.log_scalar("iter_time_s/iter", time.perf_counter() - iter_t0, iter_idx)
        tb.log_scalar("elapsed_time_s/iter", time.perf_counter() - train_t0, iter_idx)
        tb.log_scalar("throughput/elapsed_time_s", current_ep_reward, time.perf_counter() - train_t0)

    # === 训练结束后统一保存一次最终模型（仅保留最后一轮） ===
    meta = {
        "timestep": timestep,
        "updates": updates,
        "iter_idx": iter_idx,
        "episode_idx": episode_idx,
        "total_reward": total_reward,
        "tb_log_dir": tb.log_dir,
        "kwargs": kwargs,
    }
    latest = ckpt_dir / "latest.pt"
    _save_checkpoint(str(latest), agent, meta)
    # print("times:", (time.time() - st) / 3600, "hours")
    # tb.log_scalar("total_training_time", (time.time() - st) / 3600, 1)

    tb.flush()
    tb.close()
    tb.save_tb_to_csv()
    tb.plot_all()
    env.close()


def _save_checkpoint(path, agent: GRPO, meta: dict):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    payload = {
        "agent": agent.export_checkpoint(),
        "meta": meta,
        "version": 1,
    }
    torch.save(payload, path)
    print(f"[CKPT][GRPO] saved => {path}")


def _load_checkpoint(path, agent: GRPO, map_location="cpu"):
    payload = torch.load(path, map_location=map_location)
    agent.import_checkpoint(payload["agent"], map_location=map_location)
    return payload.get("meta", {})

 
if __name__ == "__main__":
    train_grpo(env_id="RSMA_ENV", max_iter=10)
    # train_grpo(env_id="TDMA_Table_ENV", max_iter=1000, mu_E_mJ=0.2)
    # # 示例：从 latest.pt 继续训练（路径按你自己的实验结果修改）
    # train_grpo(
    #     E_mean=6, lambda_arrival=3, num_devices=3, Q_max=3, B_max=12,
    #     resume_from=r"D:\7-研究生资料\小论文\MyEHC\runs\GRPO\B_max=12__E_mean=6__Q_max=3__lambda_arrival=3__max_iter=10__num_devices=3__ts=20251119-115419\checkpoints\latest.pt",
    #     max_iter=50,
    # )
