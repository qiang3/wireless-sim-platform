import math
import torch
import torch.nn as nn
from torch.distributions import MultivariateNormal

device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

class ActorOnly(nn.Module):
    def __init__(self, state_dim: int, action_dim: int, action_std_init: float):
        super().__init__()
        init_log_std = math.log(max(1e-6, float(action_std_init)))
        self.log_std = nn.Parameter(torch.full((action_dim,), init_log_std, dtype=torch.float32))
        self.actor = nn.Sequential(
            nn.Linear(state_dim, 64), nn.Tanh(),
            nn.Linear(64, 64), nn.Tanh(),
            nn.Linear(64, action_dim)
        )

    def _dist(self, mean: torch.Tensor) -> MultivariateNormal:
        std = self.log_std.exp().expand_as(mean)
        return MultivariateNormal(mean, torch.diag_embed(std.pow(2.0)))

    @torch.no_grad()
    def act(self, state: torch.Tensor):
        mean = self.actor(state)
        dist = self._dist(mean)
        raw_action = dist.rsample()
        raw_logprob = dist.log_prob(raw_action)
        return raw_action, raw_logprob

    def evaluate(self, state: torch.Tensor, raw_action: torch.Tensor):
        if raw_action.ndim == 1:
            raw_action = raw_action.unsqueeze(0)
        mean = self.actor(state)
        dist = self._dist(mean)
        raw_logprobs = dist.log_prob(raw_action)
        entropy = dist.entropy()
        return raw_logprobs, entropy
