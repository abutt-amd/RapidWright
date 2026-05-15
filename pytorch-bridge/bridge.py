################################################################################
# Copyright (c) 2026, Advanced Micro Devices, Inc.
# All rights reserved.
#
# Author: Andrew Butt, AMD Advanced Research and Development.
#
# This file is part of RapidWright.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
"""PyTorch -> FPGA accelerator JSON bridge for int8-quantized MLPs."""
from __future__ import annotations

import json
from typing import Iterable

import torch.nn as nn

_ACTIVATION_NAMES = {
    nn.ReLU: "relu",
    nn.Sigmoid: "sigmoid",
    nn.Tanh: "tanh",
}


def _leaf_modules(model: nn.Module) -> Iterable[nn.Module]:
    for module in model.modules():
        if not list(module.children()):
            yield module


def export(
    model: nn.Module,
    out_path: str,
    batch_size: int = 1,
    tile_size: int = 16,
) -> list[dict]:
    if tile_size < 1:
        raise ValueError(f"tile_size must be >= 1, got {tile_size}")
    if batch_size < 1:
        raise ValueError(f"batch_size must be >= 1, got {batch_size}")

    components: list[dict] = []
    last_width: int | None = None

    for module in _leaf_modules(model):
        if isinstance(module, nn.Linear):
            components.append({
                "type": "linear",
                "in": module.in_features,
                "out": module.out_features,
                "batch": batch_size,
                "tile_in": min(tile_size, module.in_features),
                "tile_out": min(tile_size, module.out_features),
                "bias": module.bias is not None,
                "dtype": "int8",
            })
            last_width = module.out_features
        elif type(module) in _ACTIVATION_NAMES:
            if last_width is None:
                raise ValueError(
                    f"Activation {type(module).__name__} appears before any Linear; "
                    "cannot infer width."
                )
            components.append({
                "type": _ACTIVATION_NAMES[type(module)],
                "size": last_width,
                "batch": batch_size,
                "tile": min(tile_size, last_width),
                "dtype": "int8",
            })
        else:
            raise NotImplementedError(
                f"Unsupported module: {type(module).__name__}. "
                f"Supported: nn.Linear, "
                f"{', '.join(c.__name__ for c in _ACTIVATION_NAMES)}."
            )

    with open(out_path, "w") as f:
        json.dump(components, f, indent=2)

    return components
