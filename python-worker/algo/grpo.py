import copy
from typing import List, Dict
import numpy as np
import torch
import torch.nn as nn
from algo.model import ActorOnly

device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

class GRPO:
    """
    [DIFF vs PPO]
      - 无 critic、无 GAE；优势 A 来自“组内基线”（z-score）。
      - 仍用 PPO 的 clip-ratio，但增加显式 KL(π || π_ref) 正则 + 周期性参考策略刷新。
      - 始终在与 PPO 一致的概率口径下计算 logπ：raw→tanh→[low,high] + 雅可比修正。

    [ADDED]
      - 支持 “过程监督（process-level）”：对每个时间步、跨组做 z-score，
        然后对 z-score 做折扣（γ）后的“反向累计和”，作为每步优势（不需要 value）。
      - 将 γ 作为 GRPO 的超参（默认 0.99）。
    """
    def __init__(
        self,
        state_dim: int,
        action_dim: int,
        lr_actor: float = 3e-4,
        action_std_init: float = 0.8,
        action_low=None,
        action_high=None,
        eps_clip: float = 0.2,
        entropy_coef: float = 0.01,
        kl_coef: float = 0.04,
        minibatch_size: int = 64,
        K_epochs: int = 8,
        max_grad_norm: float = 0.5,
        refresh_ref_every: int = 100,   # 每 refresh_ref_every 次更新刷新 参考策略
        gamma: float = 0.99,            # [ADDED] 过程监督的折扣因子

        entropy_decay= 0.9999,           # [ADDED] entropy coef 衰减（每次更新乘以该值）
        entropy_coef_min=0.01,         # [ADDED] entropy coef 下限
        zscore_std_floor=0.25,           # [ADDED] 逐步奖励 z-score 的最小 std（防止过拟合）
    ):
        self.eps_clip = eps_clip
        self.entropy_coef = entropy_coef
        self.kl_coef = kl_coef
        self.minibatch_size = minibatch_size
        self.K_epochs = K_epochs
        self.max_grad_norm = max_grad_norm
        self.refresh_ref_every = refresh_ref_every
        self.gamma = float(gamma)

        self.entropy_decay = float(entropy_decay)               # [ADDED]
        self.entropy_coef_min = float(entropy_coef_min)       # [ADDED]
        self.zscore_std_floor = float(zscore_std_floor)       # [ADDED]

        # [DIFF vs PPO] 仅 actor，无 critic
        self.policy = ActorOnly(state_dim, action_dim, action_std_init).to(device)
        self.policy_old = ActorOnly(state_dim, action_dim, action_std_init).to(device)
        self.policy_old.load_state_dict(self.policy.state_dict())

        # [DIFF vs PPO] 参考策略 + KL 正则
        self.policy_ref = copy.deepcopy(self.policy).to(device)

        self.optimizer = torch.optim.Adam(
            list(self.policy.actor.parameters()) + [self.policy.log_std],
            lr=lr_actor
        )

        # 动作缩放（与 PPO 完全一致）
        assert action_low is not None and action_high is not None, \
            "GRPO requires action_low/high for consistent scaling with PPO."
        action_low = torch.as_tensor(action_low, dtype=torch.float32, device=device)
        action_high = torch.as_tensor(action_high, dtype=torch.float32, device=device)
        self.action_scale = (action_high - action_low) / 2.0
        self.action_bias = (action_high + action_low) / 2.0

        self._updates = 0

    @torch.no_grad()
    def select_action(self, state_np):
        """
        与 PPO.select_action 对齐：raw→tanh→[low,high]，logπ里扣雅可比项。
        """
        state = torch.as_tensor(state_np, dtype=torch.float32, device=device).unsqueeze(0)
        raw_action, raw_logprob = self.policy_old.act(state)

        # tanh + 区间映射（与 PPO 同步）
        a_tanh = torch.tanh(raw_action)
        a_tanh = torch.clamp(a_tanh, -1.0 + 1e-6, 1.0 - 1e-6)
        action_env = (a_tanh * self.action_scale + self.action_bias).squeeze(0).cpu().numpy()

        # squashed Gaussian 的雅可比修正
        log_det_jac = torch.log(1.0 - a_tanh.pow(2) + 1e-6).sum(dim=-1)
        corrected_logprob = (raw_logprob - log_det_jac).squeeze(0)

        # 返回环境动作 + 训练需要的 raw_action/state/corrected_logprob
        return action_env, raw_action.squeeze(0).detach(), corrected_logprob.detach(), state.squeeze(0).detach()

    # ====== 组采样/更新 ======
    @staticmethod
    def _discounted_reverse_cumsum(Z: torch.Tensor, gamma: float) -> torch.Tensor:
        """
        [ADDED] 对每条序列做 反向折扣前缀和：
            A_t = Z_t + γ Z_{t+1} + γ^2 Z_{t+2} + ...
        输入:
            Z: [G, T]（已做过组内 z-score 的逐步奖励）
        """
        G, T = Z.shape
        A = torch.zeros_like(Z)
        if T == 0:
            return A
        A[:, -1] = Z[:, -1]
        for t in range(T - 2, -1, -1):
            A[:, t] = Z[:, t] + gamma * A[:, t + 1]
        return A

    def _flatten_group(self, traj_buffers: List[Dict], temperature: float = 1.0):
        """
        接收 G 条轨迹（同一“任务/起点”的多次采样）。
        每条轨迹包含：
          - states:       [T_i]  各步状态（raw）
          - actions_raw:  [T_i]  raw 动作
          - old_logprobs: [T_i]  采样时的修正后 logπ
          - step_rewards: [T_i]  [ADDED] 逐步环境奖励（过程监督）
          - R:            float  总回报（outcome 监督备用）
        返回展平批：(states, actions_raw, old_logprobs, advantages)
        """
        if "step_rewards" in traj_buffers[0] and len(traj_buffers[0]["step_rewards"]) > 0:
            # ===== 过程监督：逐时间步跨组 z-score + 折扣的反向累计和 =====
            max_T = max(len(tb["step_rewards"]) for tb in traj_buffers)
            # pad 到相同长度
            padded = []
            for tb in traj_buffers:
                r = torch.tensor(tb["step_rewards"], dtype=torch.float32)
                if len(r) < max_T:
                    r = torch.cat([r, torch.zeros(max_T - len(r))], dim=0)
                padded.append(r)
            R_mat = torch.stack(padded, dim=0)  # [G, max_T]

            # 逐步 z-score（跨组同一时间步对齐）
            mean = R_mat.mean(dim=0, keepdim=True)
            std = R_mat.std(dim=0, unbiased=False, keepdim=True)
            std = torch.clamp(std, min=self.zscore_std_floor)  # 防止过拟合
            Z = (R_mat - mean) / std

            # [ADDED] 折扣的反向累计和（credit assignment，不依赖 value）
            A_mat = self._discounted_reverse_cumsum(Z, self.gamma) * float(temperature)

            # 展平
            states = []
            actions = []
            oldlp = []
            advs = []
            for i, tb in enumerate(traj_buffers):
                T = len(tb["step_rewards"])
                states.append(torch.stack(tb["states"][:T], dim=0))
                actions.append(torch.stack(tb["actions_raw"][:T], dim=0))
                oldlp.append(torch.stack(tb["old_logprobs"][:T], dim=0))
                advs.append(A_mat[i, :T])
            return torch.cat(states), torch.cat(actions), torch.cat(oldlp), torch.cat(advs)

        # ===== outcome-only（备用）：整条轨迹一个分数，广播到每步 =====
        returns = torch.tensor([tb["R"] for tb in traj_buffers], dtype=torch.float32)
        adv_per_traj = (returns - returns.mean()) / (returns.std(unbiased=False) + 1e-8)
        adv_per_traj = adv_per_traj * float(temperature)

        all_states, all_actions, all_oldlp, all_adv = [], [], [], []
        for tb, a in zip(traj_buffers, adv_per_traj):
            T = len(tb["old_logprobs"])
            all_states.append(torch.stack(tb["states"], dim=0))
            all_actions.append(torch.stack(tb["actions_raw"], dim=0))
            all_oldlp.append(torch.stack(tb["old_logprobs"], dim=0))
            all_adv.append(torch.full((T,), float(a.item())))
        return (
            torch.cat(all_states, dim=0),
            torch.cat(all_actions, dim=0),
            torch.cat(all_oldlp, dim=0),
            torch.cat(all_adv, dim=0),
        )

    def update_group(self, traj_buffers: List[Dict], temperature: float = 1.0):
        """
        用一组（同一任务的多次采样）进行若干 epoch 的更新。
        """
        states, actions_raw, old_logprobs, advantages = self._flatten_group(traj_buffers, temperature)
        states = states.to(device); actions_raw = actions_raw.to(device)
        old_logprobs = old_logprobs.to(device); advantages = advantages.to(device)

        batch_size = states.size(0)
        mb_size = min(self.minibatch_size, batch_size)

        policy_loss_hist, entropy_hist, kl_hist = [], [], []

        for _ in range(self.K_epochs):
            idx = torch.randperm(batch_size, device=device)
            for start in range(0, batch_size, mb_size):
                mb = idx[start:start + mb_size]
                s = states[mb]; a = actions_raw[mb]
                oldlp = old_logprobs[mb]; adv = advantages[mb]

                # 当前策略 & 参考策略：raw logπ
                raw_lp_curr, entropy = self.policy.evaluate(s, a)
                raw_lp_ref, _ = self.policy_ref.evaluate(s, a)

                # 雅可比修正（与采样时一致）
                a_tanh = torch.tanh(a)
                a_tanh = torch.clamp(a_tanh, -1.0 + 1e-6, 1.0 - 1e-6)
                log_det_jac = torch.log(1.0 - a_tanh.pow(2) + 1e-6).sum(dim=-1)

                lp_curr = raw_lp_curr - log_det_jac
                lp_ref  = raw_lp_ref  - log_det_jac
                entropy_tanh = (entropy + log_det_jac).mean()   # 理论上 entropy 也要加雅可比，但意义不大

                # PPO 的 clip-ratio
                ratios = torch.exp(lp_curr - oldlp)
                surr1 = ratios * adv
                surr2 = torch.clamp(ratios, 1 - self.eps_clip, 1 + self.eps_clip) * adv
                clip_obj = -torch.min(surr1, surr2).mean()

                # 显式 KL（论文无偏估计，保证非负）:  D_KL(πθ || πref)
                # r = π_ref / π_curr = exp(lp_ref - lp_curr)
                r = torch.exp(lp_ref - lp_curr)
                # 数值稳定：避免 log(0)
                r = torch.clamp(r, min=1e-12)
                kl_unbiased = (r - torch.log(r) - 1.0).mean()

                loss = clip_obj + self.kl_coef * kl_unbiased - self.entropy_coef * entropy_tanh

                self.optimizer.zero_grad()
                loss.backward()
                if self.max_grad_norm and self.max_grad_norm > 0:
                    torch.nn.utils.clip_grad_norm_(
                        list(self.policy.actor.parameters()) + [self.policy.log_std],
                        self.max_grad_norm
                    )
                self.optimizer.step()

                policy_loss_hist.append(clip_obj.item())
                entropy_hist.append(entropy_tanh.mean().item())
                kl_hist.append(kl_unbiased.item())

        # 同步 old，周期性刷新参考策略
        self.policy_old.load_state_dict(self.policy.state_dict())
        self._updates += 1
        if self._updates % self.refresh_ref_every == 0:
            self.policy_ref.load_state_dict(self.policy.state_dict())

        self.entropy_coef = max(self.entropy_coef * self.entropy_decay, self.entropy_coef_min)  # [ADDED] 熵系数衰减

        def _m(x): return float(np.mean(x)) if len(x) else 0.0

        with torch.no_grad():
            ls = self.policy.log_std.detach()
            std = ls.exp()
            q = torch.quantile(ls, torch.tensor([0.10, 0.50, 0.90], device=ls.device))
        ret = {
            "policy_loss": _m(policy_loss_hist),
            "entropy": _m(entropy_hist),
            "kl_ref": _m(kl_hist),
            # NEW:
            "policy/log_std_mean": float(ls.mean().item()),
            "policy/log_std_p10": float(q[0].item()),
            "policy/log_std_p50": float(q[1].item()),
            "policy/log_std_p90": float(q[2].item()),
            "policy/std_mean": float(std.mean().item()),
        }
        return ret

    # ====== 导出/导入（含参考策略）======
    def export_checkpoint(self) -> dict:
        return {
            "policy": self.policy.state_dict(),
            "policy_old": self.policy_old.state_dict(),
            "policy_ref": self.policy_ref.state_dict(),
            "optimizer": self.optimizer.state_dict(),
            "action_scale": self.action_scale.detach().cpu(),
            "action_bias": self.action_bias.detach().cpu(),
            "updates": self._updates,
        }

    def import_checkpoint(self, state: dict, map_location="cpu"):
        self.policy.load_state_dict(state["policy"])
        self.policy_old.load_state_dict(state["policy_old"])
        self.policy_ref.load_state_dict(state["policy_ref"])
        self.optimizer.load_state_dict(state["optimizer"])
        self.action_scale = state["action_scale"].to(device)
        self.action_bias = state["action_bias"].to(device)
        self._updates = int(state.get("updates", 0))