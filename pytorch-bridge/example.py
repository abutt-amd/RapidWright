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
"""Toy MLP exported to accel.json."""
import torch.nn as nn

from bridge import export

model = nn.Sequential(
    nn.Linear(784, 256),
    nn.ReLU(),
    nn.Linear(256, 64),
    nn.ReLU(),
    nn.Linear(64, 10),
)

export(model, "accel.json", batch_size=32, tile_size=16)
export(model, "accel_32_32.json", batch_size=32, tile_size=32)
export(model, "accel_32_16.json", batch_size=32, tile_size=16)
export(model, "accel_16_32.json", batch_size=16, tile_size=32)


# components = export(model, "accel.json", batch_size=32, tile_size=16)
# for c in components:
#     print(c)






