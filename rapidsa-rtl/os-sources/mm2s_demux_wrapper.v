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

// Pure Verilog wrapper for mm2s_demux SystemVerilog module

module mm2s_demux_wrapper #(
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH  = 8
)(
    input  wire clk,
    input  wire rst_n,
    input wire test,

    input  wire sel,

    // From DTU (slave side)
    input  wire [DATA_WIDTH-1:0] s_data,
    input  wire [TAG_WIDTH-1:0]  s_tag,
    input  wire                  s_valid,
    output wire                  s_ready,

    // Port A (master side)
    output wire [DATA_WIDTH-1:0] m_data_a,
    output wire [TAG_WIDTH-1:0]  m_tag_a,
    output wire                  m_valid_a,
    input  wire                  m_ready_a,

    // Port B (master side)
    output wire [DATA_WIDTH-1:0] m_data_b,
    output wire [TAG_WIDTH-1:0]  m_tag_b,
    output wire                  m_valid_b,
    input  wire                  m_ready_b
);

    mm2s_demux #(
        .DATA_WIDTH(DATA_WIDTH),
        .TAG_WIDTH(TAG_WIDTH)
    ) inst (
        .clk       (clk),
        .rst_n     (rst_n),

        .sel       (sel),

        .s_data    (s_data),
        .s_tag     (s_tag),
        .s_valid   (s_valid),
        .s_ready   (s_ready),

        .m_data_a  (m_data_a),
        .m_tag_a   (m_tag_a),
        .m_valid_a (m_valid_a),
        .m_ready_a (m_ready_a),

        .m_data_b  (m_data_b),
        .m_tag_b   (m_tag_b),
        .m_valid_b (m_valid_b),
        .m_ready_b (m_ready_b)
    );

endmodule
