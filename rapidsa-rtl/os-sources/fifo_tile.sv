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
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 02/23/2026 10:52:42 AM
// Design Name: 
// Module Name: fifo_tile
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module fifo_tile #(
    parameter DWIDTH = 8,
    parameter PTR_SIZE = 6,
    parameter NUM_FIFOS = 4
)(
    input logic reset,
    input logic clk,
    
    // Write port
    input logic wr_en [NUM_FIFOS-1:0],
    input logic [DWIDTH-1:0] din [NUM_FIFOS-1:0],
  
    // Read port
    input logic rd_en [NUM_FIFOS-1:0],
    output logic [DWIDTH-1:0] dout [NUM_FIFOS-1:0],
  
    // Status
    output logic [PTR_SIZE:0] count [NUM_FIFOS-1:0],
    output logic empty [NUM_FIFOS-1:0],
    output logic full [NUM_FIFOS-1:0]
);

    genvar i;
    generate
        for (i=0; i < NUM_FIFOS; i++) begin
            fifo #(
                .DWIDTH(DWIDTH),
                .PTR_SIZE(PTR_SIZE)
            ) fifo_inst (
                .reset(reset),
                .clk(clk),
                .wr_en(wr_en[i]),
                .din(din[i]),
                .rd_en(rd_en[i]),
                .dout(dout[i]),
                .count(count[i]),
                .empty(empty[i]),
                .full(full[i])
            );
        end
    endgenerate
endmodule
