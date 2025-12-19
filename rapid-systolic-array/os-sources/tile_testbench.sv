`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 10/20/2025 10:01:56 AM
// Design Name: 
// Module Name: tile_testbench
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


module tile_testbench();

logic clk;
logic reset;

localparam BITS = 32;
localparam WIDTH = 2;
localparam HEIGHT = 2;

logic [BITS-1:0] west_inputs [0:HEIGHT-1];
logic [BITS-1:0] weight_inputs [0:WIDTH-1];
logic [BITS-1:0] north_inputs [0:WIDTH-1];
logic weight_shift_in [0:HEIGHT-1];
    
logic weight_shift_out [0:HEIGHT-1];
logic [BITS-1:0] east_outputs [0:HEIGHT-1];
logic [BITS-1:0] south_outputs [0:WIDTH-1];
logic [BITS-1:0] weight_outputs [0:WIDTH-1];

logic weight_shift;
assign weight_shift_in[0] = weight_shift;
assign weight_shift_in[1] = weight_shift;

tile #(
  .BITS(BITS),
  .WIDTH(2),
  .HEIGHT(2)
) uut (
    .clk(clk),
    .reset(reset),
    .west_inputs(west_inputs),
    .weight_inputs(weight_inputs),
    .north_inputs(north_inputs),
    .weight_shift_in(weight_shift_in),
    .weight_shift_out(weight_shift_out),
    .east_outputs(east_outputs),
    .south_outputs(south_outputs),
    .weight_outputs(weight_outputs)
);

initial begin
  clk = 0;
  forever #5 clk = ~clk;
end

logic [BITS-1:0] weights [0:WIDTH-1][0:HEIGHT-1];
logic [BITS-1:0] activations [0:WIDTH-1][0:HEIGHT-1];

always_comb begin
  // {{1.0, 2.0}, {3.0, 4.0}}
  weights = {{'h3f800000, 'h40000000}, {'h40400000, 'h40800000}};
  // {{5.0, 6.0}, {7.0, 8.0}}
  activations = {{'h40a00000, 'h40c00000}, {'h40e00000, 'h41000000}};
end

logic [BITS-1:0] results [0:WIDTH-1][0:HEIGHT-1];

initial begin
  reset = 1;
  #50;
  reset = 0;
  north_inputs = {0, 0};
  west_inputs = {0, 0};
  weight_shift = 0;
  #10;
  weight_inputs = {weights[1][0], 0};
  weight_shift = 1;
  #10;
  weight_inputs = {weights[0][0], weights[1][1]};
  #10;
  weight_shift = 0;
  weight_inputs = {0, weights[0][1]};
  #40;
  west_inputs = {activations[0][0], 0};
  #10;
  west_inputs = {activations[1][0], 0};
  #30;
  west_inputs = {0, activations[0][1]};
  #10;
  west_inputs = {0, activations[1][1]};
  #30;
  results[0][0] = south_outputs[0];
  #10;
  results[1][0] = south_outputs[0];
  results[0][1] = south_outputs[1];
  #10;
  results[1][1] = south_outputs[1];
  #100;
  
  $finish(0);
end
endmodule
