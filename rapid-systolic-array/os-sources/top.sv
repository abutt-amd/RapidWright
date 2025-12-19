`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 09/10/2025 01:36:44 PM
// Design Name: 
// Module Name: top
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


module top #(
    BITS = 8,
    TILE_HEIGHT = 4,
    TILE_WIDTH = 4,
    NUM_TILES_TALL = 46,
    NUM_TILES_WIDE = 12
)(
    input logic clk,
    input logic accum_shift,
    input logic [BITS-1:0] west_inputs [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],            
    input logic [BITS-1:0] accum_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1],
    input logic [BITS-1:0] north_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1],
    output logic [BITS-1:0] south_outputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1]
);

logic clk_buf;
logic clk_ibuf;
logic reset_ibuf;
logic accum_shift_ibuf;

IBUF clk_IBUF_inst (
  .I(clk),
  .O(clk_ibuf)
);

BUFG BUFG_inst (
  .I(clk_ibuf),
  .O(clk_buf)
);

//IBUF reset_IBUF_inst (
//  .I(reset),
//  .O(reset_ibuf)
//);

//IBUF weight_shift_IBUF_inst (
//  .I(weight_shift),
//  .O(weight_shift_ibuf)
//);

//genvar i;
//generate
//for (i = 0; i < NUM_TILES_TALL * TILE_HEIGHT; i++) begin
//  assign west_inputs[i] = i;
//end
//endgenerate

//generate
//for (i = 0; i < NUM_TILES_WIDE * TILE_WIDTH; i++) begin
//  assign weight_inputs[i] = i;
//  assign north_inputs[i] = 'd0;
//end
//endgenerate
//logic accum_shift_tmp;
//logic [BITS-1:0] west_inputs_tmp [0:(NUM_TILES_TALL * TILE_HEIGHT)-1];           
//logic [BITS-1:0] accum_inputs_tmp [0:(NUM_TILES_WIDE * TILE_WIDTH)-1];
//logic [BITS-1:0] north_inputs_tmp [0:(NUM_TILES_WIDE * TILE_WIDTH)-1];
//logic [BITS-1:0] south_outputs_tmp [0:(NUM_TILES_WIDE * TILE_WIDTH)-1];

//always_ff @(posedge clk_buf) begin
//  accum_shift_tmp <= accum_shift;
//  west_inputs_tmp <= west_inputs;
//  accum_inputs_tmp <= accum_inputs;
//  north_inputs_tmp <= north_inputs;
//  south_outputs <= south_outputs_tmp;
//end

(* dont_touch = "yes" *) systolic_array #(
    .BITS(BITS),
    .TILE_HEIGHT(TILE_HEIGHT),
    .TILE_WIDTH(TILE_WIDTH),
    .NUM_TILES_TALL(NUM_TILES_TALL),
    .NUM_TILES_WIDE(NUM_TILES_WIDE)
) u_systolic_array (
    .west_inputs(west_inputs),              
    .accum_inputs(accum_inputs), 
    .north_inputs(north_inputs),
    .clk(clk_buf),
    .accum_shift(accum_shift),
    
    .south_outputs(south_outputs)
);
endmodule
