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

module skid_buffer #(
    parameter WIDTH = 8
)(
    input  logic             clk,
    input  logic             rst_n,

    input  logic [WIDTH-1:0] s_data,
    input  logic             s_valid,
    output logic             s_ready,

    output logic [WIDTH-1:0] m_data,
    output logic             m_valid,
    input  logic             m_ready
);

    logic [WIDTH-1:0] buf_data;
    logic             buf_valid;

    // All outputs are purely registered — no combinational pass-through
    assign s_ready = !buf_valid;
    assign m_data  = buf_data;
    assign m_valid = buf_valid;

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            buf_valid <= 1'b0;
        end else if (!buf_valid || m_ready) begin
            buf_data  <= s_data;
            buf_valid <= s_valid;
        end
    end

endmodule
