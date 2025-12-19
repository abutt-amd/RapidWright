`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 10/16/2025 04:10:34 PM
// Design Name: 
// Module Name: mlp
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

module fifo #(
  parameter DWIDTH = 32,
  parameter DEPTH = 1
)(
  input logic reset,
  input logic clk,
  
  // Write port
  input logic wr_en,
  input logic [DWIDTH-1:0] din,
  
  // Read port
  input logic rd_en,
  output logic [DWIDTH-1:0] dout,
  
  // Status
  output logic empty,
  output logic full
);

  logic [$clog2(DEPTH)-1:0] wptr;
  logic [$clog2(DEPTH)-1:0] rptr;
  
  logic [DWIDTH-1:0] fifo[DEPTH];
  
  logic full_inner;
  logic empty_inner;
  
  // Write to fifo
  always_ff @(posedge clk) begin
    if (reset) begin
      wptr <= 0;
    end else begin
      if (wr_en & !full_inner) begin
        fifo[wptr] <= din;
        wptr <= (wptr > DEPTH - 1) ? 0 : wptr + 1;
      end
    end
  end
  
  always_ff @(posedge clk) begin
    if (reset) begin
      rptr <= 0;
    end else begin
      if (rd_en & !empty_inner) begin
        dout <= fifo[rptr];
        rptr <= (rptr > DEPTH - 1) ? 0 : wptr + 1;
      end
    end
  end
  
  assign full_inner = (wptr + 1) == rptr;
  assign empty_inner = wptr == rptr;
  assign full = full_inner;
  assign empty = empty_inner;
endmodule