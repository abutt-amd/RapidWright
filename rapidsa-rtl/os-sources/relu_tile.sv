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

// Parallel ReLU tile: NUM_LANES signed DATA_BITS-bit lanes pass through a
// per-lane sign mux (negative -> 0) and a single pipeline register.
// A single shared valid bit gates the registered output.
module relu_tile #(
    parameter int NUM_LANES = 16,
    parameter int DATA_BITS = 8
)(
    input  logic                            clk,
    input  logic                            reset,

    input  logic [DATA_BITS-1:0]            data_in       [NUM_LANES],
    input  logic                            data_in_valid,

    output logic [DATA_BITS-1:0]            data_out      [NUM_LANES],
    output logic                            data_out_valid
);

    logic [DATA_BITS-1:0] data_out_reg [NUM_LANES];
    logic                 data_out_valid_reg;

    always_ff @(posedge clk) begin
        if (reset) begin
            data_out_valid_reg <= 1'b0;
            for (int i = 0; i < NUM_LANES; i++) begin
                data_out_reg[i] <= '0;
            end
        end else begin
            data_out_valid_reg <= data_in_valid;
            for (int i = 0; i < NUM_LANES; i++) begin
                // Signed ReLU: if MSB is set, the value is negative -> 0.
                data_out_reg[i] <= data_in[i][DATA_BITS-1] ? '0 : data_in[i];
            end
        end
    end

    assign data_out_valid = data_out_valid_reg;
    always_comb begin
        for (int i = 0; i < NUM_LANES; i++) begin
            data_out[i] = data_out_reg[i];
        end
    end

endmodule
