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
// Wide Data Tag Unit Wrapper - Pure Verilog wrapper for SystemVerilog module

module wide_data_tag_unit_wrapper #(
    parameter AXIS_WIDTH    = 128,
    parameter MATRIX_HEIGHT = 32,
    parameter MATRIX_WIDTH  = 32,
    parameter DATA_WIDTH    = 8,
    parameter TAG_WIDTH     = 8
) (
    input  wire                    clk,
    input  wire                    rst_n,

    input  wire                    tag_source_in,
    input  wire                    clr_en,

    // AXI-Stream input
    input  wire [AXIS_WIDTH-1:0]   s_axis_tdata,
    input  wire                    s_axis_tvalid,
    output wire                    s_axis_tready,

    // Tagged output
    output wire [AXIS_WIDTH-1:0]   m_data,
    output wire [AXIS_WIDTH-1:0]   m_tag,
    output wire                    m_valid,
    output wire                    m_valid_a,
    output wire                    m_valid_b,
    input  wire                    m_ready
);

    wide_data_tag_unit #(
        .AXIS_WIDTH(AXIS_WIDTH),
        .MATRIX_HEIGHT(MATRIX_HEIGHT),
        .MATRIX_WIDTH(MATRIX_WIDTH),
        .DATA_WIDTH(DATA_WIDTH),
        .TAG_WIDTH(TAG_WIDTH)
    ) inst (
        .clk            (clk),
        .rst_n          (rst_n),

        .tag_source_in  (tag_source_in),
        .clr_en         (clr_en),

        .s_axis_tdata   (s_axis_tdata),
        .s_axis_tvalid  (s_axis_tvalid),
        .s_axis_tready  (s_axis_tready),

        .m_data         (m_data),
        .m_tag          (m_tag),
        .m_valid        (m_valid),
        .m_valid_a      (m_valid_a),
        .m_valid_b      (m_valid_b),
        .m_ready        (m_ready)
    );

endmodule
