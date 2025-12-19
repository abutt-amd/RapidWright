`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 10/16/2025 04:30:06 PM
// Design Name: 
// Module Name: relu
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

module fp32_gt(
  input clk,
  input [31:0] left,
  input [31:0] right,
  output out
);

  logic [7:0] result;
  floating_point_1 gt0 (
    .aclk(clk),
    .s_axis_a_tdata(left),
    .s_axis_a_tvalid(1'b1),
    .s_axis_b_tdata(right),
    .s_axis_b_tvalid(1'b1),
    .m_axis_result_tdata(result),
    .m_axis_result_tvalid()
  );

  assign out = result[0];
endmodule

module relu #(
  parameter DWIDTH = 32
)(
  input clk,
  input [DWIDTH-1:0] in_data,
  output [DWIDTH-1:0] out_data
);

  logic gt_zero;
  fp32_gt gt0 (
    .clk(clk),
    .left(in_data),
    .right('d0),
    .out(gt_zero)
  );

  assign out_data = gt_zero ? in_data : 0;
endmodule
