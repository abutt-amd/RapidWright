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
// Output register module for MM2S NOC channel.
// Pure Verilog so it can be instantiated directly in a Block Design.
//
// - Demuxes the DMA tvalid signal to m_valid_a / m_valid_b based on
//   active_channel, then registers the result.
// - Registers the FSM a_rd_en / b_rd_en / output_wr_en outputs.
// - Provides an inverted reset for the SA FSM.
//
// Registering the outputs at the BD boundary breaks long combinational paths
// from MM2S internal logic (sequencer state, FSM counters/comparators) through
// the long inter-module route to the edge buffer / drain tile control logic.

module valid_demux (
    input  wire clk,
    input  wire tvalid,
    input  wire active_channel,
    input  wire resetn,
    input  wire a_rd_en_in,
    input  wire b_rd_en_in,
    input  wire output_wr_en_in,

    output reg  m_valid_a,
    output reg  m_valid_b,
    output reg  a_rd_en,
    output reg  b_rd_en,
    output reg  output_wr_en,
    output wire reset
);

    assign reset = ~resetn;

    always @(posedge clk) begin
        m_valid_a    <= tvalid & ~active_channel;
        m_valid_b    <= tvalid &  active_channel;
        a_rd_en      <= a_rd_en_in;
        b_rd_en      <= b_rd_en_in;
        output_wr_en <= output_wr_en_in;
    end

endmodule
