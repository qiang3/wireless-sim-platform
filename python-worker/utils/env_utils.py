from utils.wrapping_env import wrapping_env
import os
import sys


def create_env(env_name, **kwargs):
    """
    动态创建环境。
    如果要用你上传的 MyCustomEnv，项目里需要有:
        envs/my_custom_env.py
    并且文件里要有类：
        class MyCustomEnv(...)
    """

    # --- 先取出奖励缩放参数，避免传进环境构造器 ---  # NEW
    reward_scale = kwargs.pop("reward_scale", None)  # NEW: 线性缩放倍率 c
    reward_shift = kwargs.pop("reward_shift", None)  # NEW: 线性平移 b
    # （可选）给 TimeLimit：优先用用户手动传的 max_episode_steps，其次用 N
    max_episode_steps = kwargs.get("max_episode_steps", None) or kwargs.get("N", None)  # NEW

    # eg. MyCustomEnv -> my_custom_env.py
    env_name_data = env_name.lower()

    # 查找 env 文件夹
    current_dir = os.path.dirname(os.path.abspath(__file__))
    env_path = os.path.join(current_dir, "..", "env")
    sys.path.append(env_path)

    try:
        file = __import__(env_name_data)
    except ImportError:
        raise NotImplementedError(f"Environment {env_name_data}.py not found in env/ folder.")

    # eg. MyCustomEnv -> MyCustomEnv class
    env_class_name = formatter(env_name)

    if hasattr(file, "env_creator"):
        env_class = getattr(file, "env_creator")
        env = env_class(**kwargs)
    elif hasattr(file, env_class_name):
        env_class = getattr(file, env_class_name)
        env = env_class(**kwargs)
    else:
        raise NotImplementedError(f"Environment class {env_class_name} not defined in {env_name_data}.py")

    env = wrapping_env(
        env=env,
        max_episode_steps=max_episode_steps,
        reward_shift=reward_shift,
        reward_scale=reward_scale,
    )  # NEW

    print(f"Created environment: {env_class_name}")
    return env


def formatter(src: str, firstUpper: bool = True):
    """
    把 my_custom_env 转成 MyCustomEnv
    """
    arr = src.split("_")
    res = "".join([i.capitalize() for i in arr])
    if not firstUpper:
        res = res[0].lower() + res[1:]
    return res


def soft_update(target, source, tau):
    """
    软更新 target 网络
    target = tau * source + (1 - tau) * target
    """
    for target_param, param in zip(target.parameters(), source.parameters()):
        target_param.data.copy_(tau * param.data + (1.0 - tau) * target_param.data)


def hard_update(target, source):
    """
    硬更新 target 网络
    target = source
    """
    for target_param, param in zip(target.parameters(), source.parameters()):
        target_param.data.copy_(param.data)

def _obs_from_reset(ret):
    # 兼容 gym(old): obs      和 gymnasium(new)/你包过的: (obs, info)
    return ret[0] if isinstance(ret, tuple) else ret

def _unpack_step(ret):
    # 兼容 gym(old): obs, r, done, info
    #       gymnasium(new): obs, r, terminated, truncated, info
    if isinstance(ret, (tuple, list)) and len(ret) == 5:
        obs, r, terminated, truncated, info = ret
        done = bool(terminated or truncated)
        return obs, r, done, info
    else:
        return ret  # 已经是 4 返回