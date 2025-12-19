`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 09/09/2025 04:15:50 PM
// Design Name: 
// Module Name: systolic_array
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


module systolic_array #(
  parameter BITS = 32,
  parameter TILE_HEIGHT = 4,
  parameter TILE_WIDTH = 4,
  parameter NUM_TILES_TALL = 4,
  parameter NUM_TILES_WIDE = 4
)(
    input logic [BITS-1:0] west_inputs [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],              
    input logic [BITS-1:0] weight_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1], 
    input logic [BITS-1:0] north_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1],
    input logic clk,
    input logic reset,
    input logic weight_shift,
    
    output logic [BITS-1:0] south_outputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1]
);

  logic [BITS-1:0] inter_south_outputs  [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic [BITS-1:0] inter_weight_outputs [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic [BITS-1:0] inter_east_outputs   [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic inter_weight_shift [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
    

  genvar i, j, m, n;
  generate
  for (j = 0; j < NUM_TILES_WIDE; j++) begin : x
    for (i = 0; i < NUM_TILES_TALL; i++) begin : y
    
        logic [BITS-1:0] tile_weight_inputs  [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_west_inputs    [0:TILE_HEIGHT-1];
        logic [BITS-1:0] tile_weight_outputs [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_east_outputs   [0:TILE_HEIGHT-1];
        logic [BITS-1:0] tile_north_inputs   [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_south_outputs  [0:TILE_WIDTH-1];
        logic tile_weight_shift_inputs [0:TILE_HEIGHT-1];
        logic tile_weight_shift_outputs [0:TILE_HEIGHT-1];

        for (genvar k = 0; k < TILE_WIDTH; k++) begin
          // Assign vertical tile inputs
          assign tile_north_inputs[k] = (i == 0) ? north_inputs[j * TILE_WIDTH + k] : inter_south_outputs[i-1][j][k];
          assign tile_weight_inputs[k] = (i == 0) ? weight_inputs[j * TILE_WIDTH + k] : inter_weight_outputs[i-1][j][k];
          
          // Assign vertical tile outputs
          assign inter_south_outputs[i][j][k] = tile_south_outputs[k];
          assign inter_weight_outputs[i][j][k] = tile_weight_outputs[k];
        end

        for (genvar k = 0; k < TILE_HEIGHT; k++) begin
          // Assign horizontal tile inputs
          assign tile_west_inputs[k] = (j == 0) ? west_inputs[i * TILE_HEIGHT + k] : inter_east_outputs[i][j-1][k];
          assign tile_weight_shift_inputs[k] = (j == 0) ? weight_shift : inter_weight_shift[i][j-1][k];
          
          // Assign horizontal tile outputs
          assign inter_east_outputs[i][j][k] = tile_east_outputs[k];
          assign inter_weight_shift[i][j][k] = tile_weight_shift_outputs[k];
        end


        (* dont_touch = "yes" *) tile #(
          .BITS(BITS),
          .HEIGHT(TILE_HEIGHT),
          .WIDTH(TILE_WIDTH)
        ) u_tile (
          .clk(clk),
          .reset(reset),
          .west_inputs(tile_west_inputs),
          .weight_inputs(tile_weight_inputs),
          .north_inputs(tile_north_inputs),
          .weight_shift_in(tile_weight_shift_inputs),
          .weight_shift_out(tile_weight_shift_outputs),
          .east_outputs(tile_east_outputs),
          .south_outputs(tile_south_outputs),
          .weight_outputs(tile_weight_outputs)
        );
      end
    end
  endgenerate

  genvar tile_x, pe_x;
  generate
    for (tile_x = 0; tile_x < NUM_TILES_WIDE; tile_x++) begin : output_col_tile
      for (pe_x = 0; pe_x < TILE_WIDTH; pe_x++) begin : output_pe
        assign south_outputs[tile_x*TILE_WIDTH + pe_x] = inter_south_outputs[NUM_TILES_TALL-1][tile_x][pe_x];
      end
    end
  endgenerate
endmodule
