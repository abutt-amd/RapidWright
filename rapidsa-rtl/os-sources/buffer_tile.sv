////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2026, Advanced Micro Devices, Inc.
// All rights reserved.
//
// Author: Andrew Butt, AMD Advanced Research and Development.
//
// This file is part of RapidWright.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
////////////////////////////////////////////////////////////////////////////////
`timescale 1ns / 1ps

// Simple vertical pass-through buffer. WIDTH parallel lanes of DATA_BITS-wide
// data and a per-lane valid bit are registered once between top_in and
// bot_out. Intended as an SLR-crossing register: stamp two stacked instances
// with their bot_out -> top_in chained, place each in its own SLR, and let
// the precompiled register hop carry the signal across the SLR boundary at
// high frequency.
//
// Port shape (unpacked arrays of buses) mirrors GEMMTile so the EDIF lookup
// helpers in RapidSANetlistBuilder (countArrayPorts, etc.) handle it the
// same way.
module buffer_tile #(
    parameter int WIDTH = 4,
    parameter int DATA_BITS = 8
)(
    input  logic                  clk,

    input  logic [DATA_BITS-1:0]  top_in        [0:WIDTH-1],
    input  logic                  top_in_valid  [0:WIDTH-1],

    output logic [DATA_BITS-1:0]  bot_out       [0:WIDTH-1],
    output logic                  bot_out_valid [0:WIDTH-1]
);

    logic [DATA_BITS-1:0] top_in_reg       [0:WIDTH-1];
    logic                 top_in_valid_reg [0:WIDTH-1];

    always_ff @(posedge clk) begin
        for (int i = 0; i < WIDTH; i++) begin
            top_in_reg[i]       <= top_in[i];
            top_in_valid_reg[i] <= top_in_valid[i];
        end
    end

    always_comb begin
        for (int i = 0; i < WIDTH; i++) begin
            bot_out[i]       = top_in_reg[i];
            bot_out_valid[i] = top_in_valid_reg[i];
        end
    end

endmodule
