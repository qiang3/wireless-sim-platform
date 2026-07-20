# rsma_env_fdma_style.py
import numpy as np
import math
import random
from gym import Env, spaces
import torch

# —— 统一随机种子（与 fdma_env 风格一致）——
seed = 42
random.seed(seed)
np.random.seed(seed)
torch.manual_seed(seed)
torch.cuda.manual_seed_all(seed)  # 若使用GPU

DEFAULTS = dict(
    num_devices=3,  # 设备数

    E_max=10.0,  # 单时隙能收集的最大能量（mj）
    E_mean=6,  # 能量收集均值（mj）
    E_std=1.2,  # 能量收集标准差（mj） # Throughput Maximization 论文里使用的数值
    lambda_arrival=3,  # 数据包到达率（泊松分布lambda）, Mbit/slot --> 5 Mbit/slot x 100 --> 500Mbits # MEC 的论文

    Q_max=3,  # 最大buffer容量 （Mbits）
    B_max=12.0,  # 最大电池容量（mj）

    tau=0.1,  # 单时隙时长（秒）
    N=100,  # 时隙数
    P_wpt=4,  # WPT供能功率(W)
    P_max=100,  # 无线设备最大数据传输功率（mW）, 0.1W

    d1_list=None,  # WPT与设备的距离列表
    d2_list=None,  # 设备与AP的距离列表
    d1=5,  # WPT与设备的统一距离(m)
    d2=10,  # 设备与AP的统一距离(m)
    d_coef=3.0,  # 路径损耗指数

    band=1e6,    # NB-IoT带宽（Hz） 1MHz
    # —— 噪声按“功率谱密度（W/Hz）”建模 ——
    # 若 N0=None，则按 N0 = k*T*10^(NF_dB/10) 自动计算：
    #   其中 k=1.380649e-23 (J/K)，T[K]≈290，-174 dBm/Hz ≈ 4e-21 W/Hz
    N0=None,       # 噪声功率谱密度（W/Hz）；None 表示用 kT 和 NF 自动计算
    NF_dB=7.0,     # 接收机噪声系数（dB）
    T_K=290.0,     # 物理温度（K）

    # 非线性充电参数
    P_sat=100,  # 无线设备的最大转化功率 （决定曲线上限）
    a=0.065,  # 非线性转化参数a（决定曲线的陡缓程度，越大越陡）
    b=60,  # 非线性转化参数b（确定变陡的输入功率节点）
    P_c=0.1,  # 电路功耗（mW）

    K_factor=5,  # Rician K因子
    beta=0.0,  # buffer overflow 惩罚系数，默认无惩罚
)

class RsmaEnv(Env):
    """
    上行 RSMA（两层：私有+公共，单载波）环境（FDMA 风格统一化）：
    - 观测：与 FdmaEnv 一致  [E_a, B, Q, G1, G2] × N_dev
    - 动作：action = [tc] + [Pt_1..Pt_N] + [alpha_1..alpha_N]
             其中 alpha_i 为用户 i 的“子消息1”功率系数，子消息2 为 (1-alpha_i)
    - 速率：基站对 2N 层执行串行联合解码（SIC），按信道增益排序生成 π（两层同顺序）
    - 其他流程（充电/到达/电路功耗/三重限制用时）与 FdmaEnv 对齐
    """
    metadata = {"render_modes": ["human"], "render_fps": 5}

    def __init__(self, **kwargs):
        super(RsmaEnv, self).__init__()
        cfg = {**DEFAULTS, **kwargs}

        # —— 基本参数 ——
        self.tau = float(cfg["tau"])
        self.N = int(cfg["N"])
        self.num_devices = int(cfg["num_devices"])
        self.B_max = float(cfg["B_max"])
        self.E_max = float(cfg["E_max"])
        self.E_mean = float(cfg["E_mean"])
        self.E_std = float(cfg["E_std"])
        self.Q_max = float(cfg["Q_max"])

        self.P_wpt = float(cfg["P_wpt"])
        self.P_max = float(cfg["P_max"])

        # 距离向量（与 fdma_env 相同的回退策略）
        self.d1_list = (np.full(self.num_devices, float(cfg["d1"])) if cfg["d1_list"] is None
                        else np.array(cfg["d1_list"], dtype=np.float32))
        self.d2_list = (np.full(self.num_devices, float(cfg["d2"])) if cfg["d2_list"] is None
                        else np.array(cfg["d2_list"], dtype=np.float32))
        assert len(self.d1_list) == self.num_devices and len(self.d2_list) == self.num_devices, \
            "d1_list and d2_list must match num_devices"

        self.band = float(cfg["band"])
        self.d_coef = float(cfg["d_coef"])
        # ---- 噪声 PSD 计算 ----
        self.NF_dB = float(cfg.get('NF_dB', 7.0))
        self.T_K = float(cfg.get('T_K', 290.0))
        k_B = 1.380649e-23  # J/K
        if cfg.get('N0', None) is None:
            # N0 为功率谱密度（W/Hz）
            self.N0 = k_B * self.T_K * (10.0 ** (self.NF_dB / 10.0))  # 约为 2e-20 W/Hz, -164dBm/Hz
        else:
            # 也可手动直接传入 PSD（W/Hz）
            self.N0 = float(cfg['N0'])

        # 非线性充电 / 电路功耗
        self.P_sat = float(cfg["P_sat"])
        self.a = float(cfg["a"])
        self.b = float(cfg["b"])
        self.P_c = float(cfg["P_c"])

        # 衰落、奖励与到达
        self.K_factor = float(cfg["K_factor"])
        self.beta = float(cfg["beta"])
        self.lambda_arrival = float(cfg["lambda_arrival"])
        self.lambda_arrival_rate = np.full(self.num_devices, self.lambda_arrival)

        # —— 观测空间：与 fdma_env 完全一致 ——
        obs_low = np.zeros(5 * self.num_devices, dtype=np.float32)
        obs_high = np.concatenate([
            np.full(self.num_devices, self.E_max),
            np.full(self.num_devices, self.B_max),
            np.full(self.num_devices, self.Q_max),
            np.ones(self.num_devices),
            np.ones(self.num_devices),
        ]).astype(np.float32)
        self.observation_space = spaces.Box(low=obs_low, high=obs_high, dtype=np.float32)

        # —— 动作空间：时隙分割比例 + (Pt_n_1, Pt_n2) x N + (pi_n1,pi_n2) X N --> 属于[0,1]
        act_low = np.zeros(1 + 4 * self.num_devices, dtype=np.float32)
        act_high = np.ones(1 + 4 * self.num_devices, dtype=np.float32)
        self.action_space = spaces.Box(low=act_low, high=act_high, dtype=np.float32)

        # 统计量
        self.count = 0
        self.drop = np.zeros(self.num_devices)
        self.total_drop = np.zeros(self.num_devices)
        self.throughput = np.zeros(self.num_devices)
        self.total_nature_energy = np.zeros(self.num_devices)
        self.total_wpt_energy = np.zeros(self.num_devices)

        self.Pt1 = np.zeros(self.num_devices)
        self.Pt2 = np.zeros(self.num_devices)
        self.rate_users_Mbps = np.zeros(self.num_devices)

        # 初始化
        self.reset()

    # ===== 主流程 =====
    def reset(self, seed=None, **kwargs):
        # Gym评估入口会为每个回合传入明确种子；只重置随机状态，不改变任何物理参数默认值。
        if seed is not None:
            random.seed(seed)
            np.random.seed(seed)
            torch.manual_seed(seed)
            if torch.cuda.is_available():
                torch.cuda.manual_seed_all(seed)
        self.E_a = np.zeros(self.num_devices, dtype=np.float32)  # 上一步自然能量
        self.B = np.zeros(self.num_devices, dtype=np.float32)    # 电池
        self.Q = np.zeros(self.num_devices, dtype=np.float32)    # buffer

        # self.B = np.random.randint(0, 200, size=self.num_devices)    # 电池有电
        # self.Q = np.random.randint(0, 5, size=self.num_devices)    # buffer有数据

        self.G1 = self._calc_all_power_gains(self.d1_list,self.d_coef, self.K_factor)  # WPT链路
        self.G2 = self._calc_all_power_gains(self.d2_list,self.d_coef, self.K_factor)  # 上行链路

        self.count = 0
        self.drop = np.zeros(self.num_devices, dtype=np.float32)
        self.total_drop = np.zeros(self.num_devices, dtype=np.float32)
        self.throughput = np.zeros(self.num_devices, dtype=np.float32)
        self.total_nature_energy = np.zeros(self.num_devices, dtype=np.float32)
        self.total_wpt_energy = np.zeros(self.num_devices, dtype=np.float32)

        return np.concatenate([self.E_a, self.B, self.Q, self.G1, self.G2]).astype(np.float32)

    def step(self, action):
        action = np.asarray(action, dtype=np.float32).reshape(-1)
        assert action.size == 1 + 4 * self.num_devices, \
            f"Expect action len={1 + 4*self.num_devices}, got {action.size}"

        t_alpha = float(np.clip(action[0], 0.0, 1))
        tc = t_alpha * self.tau  # WPT 用时

        Pt = np.clip(action[1:1+self.num_devices*2], 0.0, 1.0)  # (2N,)
        Pt = Pt.reshape(self.num_devices, 2) # 分层功率

        Pt1 = Pt[:,0] * self.P_max  # 子消息1 功率 (N,)
        Pt2 = Pt[:,1] * (self.P_max - Pt1) # 子消息2 功率 (N,)
        self.Pt1 = Pt1 # 记录当前功率分配
        self.Pt2 = Pt2 # 记录当前功率分配

        pi = np.clip(action[1+self.num_devices*2:], 0.0, 1.0)
        pi = pi.reshape(self.num_devices, 2)  # (N,2)
        # (N,2),数值表示解码顺序 p[i*2+j] 表示用户 i 的层 j 的解码顺序

        # 1) 自然能量入电池
        self.B = np.minimum(self.B + self.E_a, self.B_max)

        # 2) WPT 充电（非线性转换，与 fdma_env 对齐）
        Pr = self.P_wpt * self.G1
        Pe = np.array([self._non_linear_conversion(p*1e3) for p in Pr])
        charge_energy = Pe * tc  # mJ
        self.last_charge_energy = charge_energy
        self.total_nature_energy += self.E_a
        self.total_wpt_energy += charge_energy
        self.B = np.minimum(self.B + charge_energy, self.B_max)

        # 2.5) 电路能耗（与 fdma_env 对齐）：整时隙扣除
        self.B -= self.P_c * self.tau  # mJ
        self.B = np.maximum(self.B, 0.0)

        # 3) 数据到达 & 溢出
        arrivals = np.random.poisson(self.lambda_arrival_rate)
        new_Q = self.Q + arrivals
        self.drop = np.maximum(new_Q - self.Q_max, 0.0)
        self.total_drop += self.drop
        self.Q = np.minimum(new_Q, self.Q_max)

        # 4) 速率（RSMA 两层 + SIC）
        rates_user = self._calc_rate_rsma(Pt1,Pt2,self.G2,pi)  # bit/s

        # # 5) 实际传输用时（与 fdma_env 三重限制一致）
        tm = self.tau - tc if tc < self.tau else 0.0
        eps = 1e-12
        rates_user_Mbps = rates_user / 1e6  # bit/s → Mbit/s，与 Q 的 Mbits 匹配

        self.rate_users_Mbps = rates_user_Mbps  # 记录当前速率

        # print("rate_users_Mbps:", rates_user_Mbps)
        # print("sum_rates_Mbps:", np.sum(rates_user_Mbps))
        Pt_sum = (Pt1 + Pt2).reshape(-1)  # 总功率
        active = Pt_sum > eps
        # 能量上限可用时长（Pt_sum=0 视为无限制）
        t_energy = np.full_like(Pt_sum, np.inf, dtype=float)
        np.divide(self.B, np.maximum(Pt_sum, eps), out=t_energy, where=Pt_sum > eps)  # mJ / (mW) = s
        # 队列所需时长（rate=0 视为无穷大）
        t_need = np.full_like(Pt_sum, np.inf, dtype=float)
        np.divide(self.Q, np.maximum(rates_user_Mbps, eps), out=t_need, where=rates_user_Mbps > eps)
        # 实际传输用时
        t_used = np.minimum.reduce([np.full(self.num_devices, tm), t_energy, t_need])
        t_used = np.where(active, t_used, 0.0)
        sent_bits = rates_user_Mbps * t_used  # Mbits
        self.Q -= sent_bits  # Mbits
        self.B -= Pt_sum * t_used  # mW*s = mJ
        rewards_bits = sent_bits

        total_transmitted_bits = rewards_bits.sum()
        # print(f"Transmitted bits: {total_transmitted_bits}, Rewards bits: {rewards_bits}")
        self.throughput += rewards_bits

        total_reward = total_transmitted_bits - self.beta * float(np.sum(self.drop))

        # 6) 跳转到下一时隙
        samples = np.random.normal(self.E_mean, self.E_std, size=self.num_devices)
        self.E_a = np.clip(samples, 0.0, self.E_max).astype(np.float32)
        self.G1 = self._calc_all_power_gains(self.d1_list, self.d_coef, self.K_factor)
        self.G2 = self._calc_all_power_gains(self.d2_list, self.d_coef, self.K_factor)
        self.count += 1

        done = False
        info = {
            "per_device_reward_bits": rewards_bits,
            "total_reward": total_reward,
            "buffer_status_bits": self.Q.copy(),
            "total_drop_bits": self.total_drop.copy(),
            "arrivals_bits": arrivals.copy(),
            "TimeLimit.truncated": False,
        }
        if self.count >= self.N:
            done = True
            info["TimeLimit.truncated"] = True

        next_state = np.concatenate([self.E_a, self.B, self.Q, self.G1, self.G2]).astype(np.float32)
        return next_state, total_reward, done, info


    def _calc_rate_rsma(self, Pt1, Pt2, G, pi, user_idx=None, eps=1e-30):
        """
        RSMA-SISO 速率计算（两层，2N 串行联合解码 + 完美SIC）

        参数
        ----
        Pt1 : (N,)  子消息1的发射功率
        Pt2 : (N,)  子消息2的发射功率
        G   : (N,)  用户等效功率增益（|h|^2 或 |w^H h|^2）
        pi  : (2N,) 或 (N,2)  每一层的“解码优先级”，数值越小越先解（允许任意实数；不要求是0..2N-1的排列）
        user_idx : 可选，仅返回该用户的速率与分层速率

        返回
        ----
        若 user_idx 为 None:
            rates_user : (N,)  每个用户的总速率（两层相加）
            rates_layer: (N,2) 每层速率
        否则:
            (rate_user_i, rate_layers_i:(2,))
        """
        Pt1 = np.asarray(Pt1, dtype=np.float64).reshape(-1)
        Pt2 = np.asarray(Pt2, dtype=np.float64).reshape(-1)
        G = np.asarray(G, dtype=np.float64).reshape(-1)

        N = G.size
        if Pt1.shape[0] != N or Pt2.shape[0] != N:
            raise ValueError("Pt1, Pt2, G 必须同为形状 (N,)")

        # 接收功率（每层信号功率）
        S1 = Pt1 * G  # (N,)
        S2 = Pt2 * G  # (N,)
        s_mat = np.stack([S1, S2], axis=1)  # (N,2)

        # 噪声功率（与 fdma_env 对齐：Noise = band * N0）
        B = float(self.band)
        noise_power = B * float(self.N0)

        # 规范化解码顺序 π
        pi_arr = np.asarray(pi, dtype=float)
        if pi_arr.shape == (2 * N,):
            pi_flat = pi_arr
        elif pi_arr.shape == (N, 2):
            pi_flat = pi_arr.reshape(-1)
        else:
            raise ValueError("pi 需为形状 (2N,) 或 (N,2)")

        # 展平为 2N 层，并按 π 从小到大排序（先解的在前）
        s_flat = s_mat.reshape(-1)  # (2N,)
        order = np.argsort(pi_flat, kind="stable")
        s_sorted = s_flat[order]  # 依解码顺序排列的每层功率

        # 计算“尾和干扰”：对第 k 个被解码的层，其干扰是所有 k 之后层的功率之和
        tail_sum = np.cumsum(s_sorted[::-1])[::-1]  # 尾部累计和
        interf_sorted = np.zeros_like(s_sorted)
        if s_sorted.size > 1:
            interf_sorted[:-1] = tail_sum[1:]  # 最后一个层没有后继 → 干扰为 0

        # SNR 与速率
        denom_sorted = np.maximum(interf_sorted + noise_power, eps)
        snr_sorted = s_sorted / denom_sorted
        rate_sorted = B * (np.log1p(snr_sorted) / np.log(2.0))  # B·log2(1+SNR)

        # 还原原始层次顺序 -> (N,2)
        rate_flat = np.empty_like(rate_sorted)
        rate_flat[order] = rate_sorted
        rates_layer = rate_flat.reshape(N, 2)

        # 汇总每用户速率
        rates_user = rates_layer.sum(axis=1)
        return rates_user

    # ===== 能量与信道工具 =====
    def _non_linear_conversion(self, P_recv):
        fai = self.P_sat / (1 + math.exp(-self.a * (P_recv - self.b)))
        omega = 1 / (1 + math.exp(self.a * self.b))
        return (fai - self.P_sat * omega) / (1 - omega)

    def _calc_all_power_gains(self, d_list,d_coef, K_factor):
        gains = []
        for d in d_list:
            gains.append(self._calc_power_gain(d,d_coef, K_factor))
        return np.array(gains, dtype=np.float32)

    def _calc_power_gain(self, d, d_coef, K):
        path_loss = (1.0 / d) ** d_coef
        theta = np.random.uniform(0, 2 * math.pi)
        X, Y = np.random.randn(), np.random.randn()
        los = math.sqrt(K / (K + 1)) * complex(math.cos(theta), math.sin(theta))
        scat = math.sqrt(1 / (K + 1)) * complex(X, Y) / math.sqrt(2)
        h = los + scat
        small_scale = abs(h) ** 2
        return float(path_loss * small_scale)

    # ===== 调试辅助 =====
    def get_state(self):
        st = {
            "E_a": self.E_a.copy(), "B": self.B.copy(), "Q": self.Q.copy(),
            "G1": self.G1.copy(), "G2": self.G2.copy(),
            "drop": self.drop.copy(), "total_drop": self.total_drop.copy(),
            "throughput": self.throughput.copy(),
            "total_nature_energy": self.total_nature_energy.copy(),
            "total_wpt_energy": self.total_wpt_energy.copy(),
            "count": int(self.count),
            "np_rng_state": np.random.get_state(),
        }
        if hasattr(self, "last_charge_energy"):
            st["last_charge_energy"] = self.last_charge_energy.copy()
        return st

    def set_state(self, st):
        self.E_a = st["E_a"].copy()
        self.B = st["B"].copy()
        self.Q = st["Q"].copy()
        self.G1 = st["G1"].copy()
        self.G2 = st["G2"].copy()
        self.drop = st["drop"].copy()
        self.total_drop = st["total_drop"].copy()
        self.throughput = st["throughput"].copy()
        self.total_nature_energy = st["total_nature_energy"].copy()
        self.total_wpt_energy = st["total_wpt_energy"].copy()
        self.count = int(st["count"])
        if "last_charge_energy" in st:
            self.last_charge_energy = st["last_charge_energy"].copy()
        np.random.set_state(st["np_rng_state"])

    def _render_bar(self, current, total, bar_len=20):
        filled_len = int(round(bar_len * current / float(total))) if total > 0 else 0
        filled_len = max(0, min(bar_len, filled_len))
        bar = '█' * filled_len + '-' * (bar_len - filled_len)
        percent = (100.0 * current / float(total)) if total > 0 else 0.0
        return f"|{bar}| {current:.1f}/{total} ({percent:.1f}%)"

    def render(self, mode="human"):
        print(f"\nStep {self.count}/{self.N}")
        for i in range(self.num_devices):
            print(f"  设备{i + 1}:")
            print(f"    电池:   {self._render_bar(self.B[i], self.B_max)}")
            print(f"    缓冲区: {self._render_bar(self.Q[i], self.Q_max)}")
            print(f"    本步自然能量到达: {self.E_a[i]:.2f}")
            if hasattr(self, 'last_charge_energy'):
                print(f"    本步WPT充电能量: {self.last_charge_energy[i]:.2f}")
            print(f"    [累计]自然到达能量: {self.total_nature_energy[i]:.2f}")
            print(f"    [累计]WPT充电能量: {self.total_wpt_energy[i]:.2f}")
            ratio = (self.total_nature_energy[i] / (self.total_wpt_energy[i] + 1e-8))
            print(f"    自然/充电累计比值: {ratio:.2f}")
            print(f"    本步数据丢弃量: {self.drop[i]:.2f} bits")
        print('-' * 35)
        print(f"  每个设备累计吞吐量: {self.throughput} bits")
        print(f"  总数据吞吐量: {np.sum(self.throughput):.2f} bits")
        print(f"  每个设备累计数据丢弃量: {self.total_drop} bits")
        print(f"  总数据丢弃量: {np.sum(self.total_drop):.2f} bits")
        print(f"子消息1的发射功率 Pt1: {self.Pt1} mW")
        print(f"子消息2的发射功率 Pt2: {self.Pt2} mW")
        print(f"用户速率：rate_user_Mbps: {self.rate_users_Mbps} Mbit/s")
        print("="*80)


if __name__ == "__main__":
    env = RsmaEnv()
    obs = env.reset()
    done = False
    total_reward = 0.0
    while not done:
        action = np.array([0.05] + [0.5] * env.num_devices + [0.5] * env.num_devices + [x for x in np.random.rand(env.num_devices*2)], dtype=np.float32)
        obs, reward, done, info = env.step(action)
        total_reward += reward
        env.render()
    print(f"Total Reward: {total_reward}")
