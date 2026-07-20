from torch.utils.tensorboard import SummaryWriter
import os, re, json, hashlib
import numpy as np
from datetime import datetime
import numpy as np
import pandas as pd
import os
import time
import webbrowser
import platform
import signal
from pathlib import Path
import tensorboard.backend.application

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

def _now_ts(fmt="%Y%m%d-%H%M%S"):
    # 使用系统本地时间
    return datetime.now().strftime(fmt)

def _fmt_val(v):
    if isinstance(v, float):
        return f"{v:.4g}"
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(_fmt_val(x) for x in v) + "]"
    return str(v)

def _slug(s: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]", "-", s)

def _build_param_subdir(kwargs: dict, max_len: int = 120) -> str:
    if not kwargs:
        return "default"
    items = [(str(k), _fmt_val(v)) for k, v in kwargs.items()]
    items.sort(key=lambda x: x[0])
    kv = "__".join(f"{_slug(k)}={_slug(v)}" for k, v in items)
    if len(kv) <= max_len:
        return kv
    cfg_json = json.dumps({k: v for k, v in kwargs.items()},
                          sort_keys=True, default=str, ensure_ascii=False)
    short = hashlib.sha1(cfg_json.encode("utf-8")).hexdigest()[:10]
    return f"cfg-{short}"

def _ensure_dir(d):
    os.makedirs(d, exist_ok=True)
    return d

def _sanitize_tag(name: str) -> str:
    # 兼容 TB 的 "a/b" 层级式 tag
    return name.replace("\\", "/").replace("/", "_")
def _ema(y: np.ndarray, alpha: float) -> np.ndarray:
    """
    指数滑动平均；alpha∈(0,1)，越靠近 1 越“追踪原曲线”，0 不平滑
    """
    if not (0 < alpha < 1):
        return y
    out = np.empty_like(y, dtype=float)
    if len(y) == 0:
        return out
    out[0] = y[0]
    for i in range(1, len(y)):
        out[i] = alpha * y[i] + (1 - alpha) * out[i - 1]
    return out


class TBLogger:
    def __init__(self, run_name="default",
                 save_hparams=False, add_timestamp=True, ts_key="ts", resume_log_dir: str = None,  **kwargs):

        SCRIPT_DIR = Path(__file__).resolve().parent  # 这个.py文件所在的文件夹
        BASE_DIR = (SCRIPT_DIR / "../runs").resolve()

        # 如果给了 resume_log_dir，就直接复用，不再拼新目录
        if resume_log_dir is not None:
            self.log_dir = str(resume_log_dir)
            self.run_name = run_name
            self.kwargs = kwargs
            os.makedirs(self.log_dir, exist_ok=True)
            self.writer = SummaryWriter(self.log_dir, flush_secs=10, max_queue=20)
            return

        self.run_name = run_name
        # 在参数中加入系统本地时间（若已传同名键则不覆盖）
        if add_timestamp:
            kwargs = dict(kwargs)
            kwargs.setdefault(ts_key, _now_ts())  # 例如 20250818-204215
        self.kwargs = kwargs

        run_root = os.path.join(BASE_DIR, _slug(run_name))
        os.makedirs(run_root, exist_ok=True)

        param_subdir = _build_param_subdir(kwargs)
        log_dir = os.path.join(run_root, param_subdir)
        os.makedirs(log_dir, exist_ok=True)

        self.log_dir = log_dir
        self.writer = SummaryWriter(log_dir,flush_secs=10, max_queue=20)

        if save_hparams:
            meta = {"run_name": run_name, "param_subdir": param_subdir, "hparams": kwargs}
            with open(os.path.join(log_dir, "hparams.json"), "w", encoding="utf-8") as f:
                json.dump(meta, f, ensure_ascii=False, indent=2, default=str)
            try:
                self.writer.add_text("hparams/json",
                                     "```json\n" + json.dumps(kwargs, ensure_ascii=False, indent=2, default=str) + "\n```",
                                     global_step=0)
            except Exception:
                pass

    def log_scalar(self, tag, value, step):
        self.writer.add_scalar(tag, value, step)

    def log_hist(self, tag, values, step, bins='auto'):
        if values is None:
            return
        if not isinstance(values, (list, tuple, np.ndarray)):
            try:
                values = values.detach().cpu().numpy()
            except Exception:
                values = np.array([values], dtype=float)
        self.writer.add_histogram(tag, np.asarray(values), step)

    def flush(self):
        self.writer.flush()

    def close(self):
        self.writer.close()

    def read_tensorboard(self, path):
        """
        Input dir of tensorboard log.
        """
        import tensorboard
        from tensorboard.backend.event_processing import event_accumulator

        tensorboard.backend.application.logger.setLevel("ERROR")
        ea = event_accumulator.EventAccumulator(path)
        ea.Reload()
        valid_key_list = ea.scalars.Keys()

        output_dict = dict()
        for key in valid_key_list:
            event_list = ea.scalars.Items(key)
            x, y = [], []
            for e in event_list:
                x.append(e.step)
                y.append(e.value)

            data_dict = {"x": np.array(x), "y": np.array(y)}
            output_dict[key] = data_dict
        return output_dict


    def save_csv(self, path, step, value):
        """
        Save 2-column-data to csv.
        """
        df = pd.DataFrame({"Step": step, "Value": value})
        df.to_csv(path, index=False, sep=",")

    def save_tb_to_csv(self):
        """
        Parse all tensorboard log file in given dir (e.g. ./results),
        and save all data as csv.
        """
        path = self.log_dir
        data_dict = self.read_tensorboard(path)
        for data_name in data_dict.keys():
            data_name_format = data_name.replace("\\", "/").replace("/", "_")
            csv_dir = os.path.join(path, "data")
            os.makedirs(csv_dir, exist_ok=True)
            self.save_csv(
                os.path.join(csv_dir, "{}.csv".format(data_name_format)),
                step=data_dict[data_name]["x"],
                value=data_dict[data_name]["y"],
            )

    # --- NEW: 保存单张图片 ---
    def save_picture(
            self,
            x: np.ndarray,
            y: np.ndarray,
            out_path: str,
            title: str = None,
            xlabel: str = "Step",
            ylabel: str = "Value",
            smooth: float = 0.0,
            dpi: int = 150,
            linewidth: float = 1.8,
            figsize=(8, 4.5),
            grid: bool = True,
    ):
        """
        将 (x, y) 绘制为折线图并保存。
        smooth: EMA 系数 (0 表示不平滑；建议 0.5~0.95)
        """
        if x is None or y is None or len(x) == 0:
            return

        y_plot = _ema(np.asarray(y, dtype=float), smooth) if smooth and 0 < smooth < 1 else np.asarray(y,
                                                                                                       dtype=float)

        _ensure_dir(os.path.dirname(out_path))
        plt.figure(figsize=figsize)
        plt.plot(x, y_plot, linewidth=linewidth)
        if grid:
            plt.grid(True, linestyle="--", alpha=0.4)
        plt.xlabel(xlabel)
        plt.ylabel(ylabel)
        if title:
            plt.title(title)
        plt.tight_layout()
        plt.savefig(out_path, dpi=dpi)
        plt.close()


    def plot_all(
        self,
        smooth: float = 0.0,
        dpi: int = 150,
        linewidth: float = 1.8,
        figsize=(8, 4.5),
        make_overview: bool = False,
        overview_max_tags: int = 10,
    ):
        """
        Plot all tensorboard scalars in the current run directory to PNG files.
        每个 tag 生成一张图，保存在 {log_dir}/picture/{tag}.png
        可选生成总览图 {log_dir}/picture/_overview.png（前 N 条曲线叠加）
        返回：保存的图片路径列表
        """
        path = self.log_dir
        data_dict = self.read_tensorboard(path)

        picture_dir = _ensure_dir(os.path.join(path, "picture"))
        saved_files = []

        # 单图逐个输出
        for data_name, item in data_dict.items():
            data_name_format = _sanitize_tag(data_name)
            out_path = os.path.join(picture_dir, f"{data_name_format}.png")
            self.save_picture(
                x=item["x"],
                y=item["y"],
                out_path=out_path,
                title=data_name,
                smooth=smooth,
                dpi=dpi,
                linewidth=linewidth,
                figsize=figsize,
            )
            saved_files.append(out_path)

        # 叠加预览图（最多 overview_max_tags 条）
        if make_overview and len(data_dict) > 0:
            plt.figure(figsize=(9.5, 5.5))
            # 取“数据点最多”的前 N 条
            tags_sorted = sorted(data_dict.items(), key=lambda kv: len(kv[1]["x"]), reverse=True)
            for i, (tag, item) in enumerate(tags_sorted[:overview_max_tags]):
                y_plot = _ema(item["y"], smooth) if smooth and 0 < smooth < 1 else item["y"]
                plt.plot(item["x"], y_plot, label=tag, linewidth=1.4)
            plt.grid(True, linestyle="--", alpha=0.4)
            plt.xlabel("Step")
            plt.ylabel("Value")
            plt.title("Overview")
            plt.legend(fontsize=8, loc="best")
            plt.tight_layout()
            overview_path = os.path.join(picture_dir, "_overview.png")
            plt.savefig(overview_path, dpi=dpi)
            plt.close()
            saved_files.append(overview_path)

        return saved_files

    def max_step(self, tag_prefix: str = None) -> int:
        """
        扫描当前 log_dir 所有 scalars，返回最大的 step；若目录为空，返回 -1。
        可用 tag_prefix 限制到某些 tag。
        """
        try:
            data = self.read_tensorboard(self.log_dir)
            mx = -1
            for k, item in data.items():
                if tag_prefix is not None and not _sanitize_tag(k).startswith(_sanitize_tag(tag_prefix)):
                    continue
                if len(item["x"]) > 0:
                    mx = max(mx, int(item["x"][-1]))
            return mx
        except Exception:
            return -1
