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


module slr_crossing #(
  parameter BITS = 8,
  parameter TILE_HEIGHT = 4,
  parameter TILE_WIDTH = 4,
  parameter NUM_TILES_TALL = 2,
  parameter NUM_TILES_WIDE = 1
)(
    input logic [BITS-1:0] west_inputs [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],              
    input logic [BITS-1:0] accum_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1], 
    input logic [BITS-1:0] north_inputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1],
    input logic clk,
    input logic accum_shift_in [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],
    
    output logic accum_shift_out [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],
    
    output logic [BITS-1:0] east_outputs [0:(NUM_TILES_TALL * TILE_HEIGHT)-1],              
    output logic [BITS-1:0] accum_outputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1], 
    output logic [BITS-1:0] south_outputs [0:(NUM_TILES_WIDE * TILE_WIDTH)-1]
);

  logic [BITS-1:0] inter_south_outputs  [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic [BITS-1:0] inter_accum_outputs [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic [BITS-1:0] inter_east_outputs   [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
  logic inter_accum_shift [0:NUM_TILES_TALL-1][0:NUM_TILES_WIDE-1][0:TILE_WIDTH-1];
    

  genvar i, j, m, n;
  generate
  for (j = 0; j < NUM_TILES_WIDE; j++) begin : x
    for (i = 0; i < NUM_TILES_TALL; i++) begin : y
    
        logic [BITS-1:0] tile_accum_inputs  [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_west_inputs    [0:TILE_HEIGHT-1];
        logic [BITS-1:0] tile_accum_outputs [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_east_outputs   [0:TILE_HEIGHT-1];
        logic [BITS-1:0] tile_north_inputs   [0:TILE_WIDTH-1];
        logic [BITS-1:0] tile_south_outputs  [0:TILE_WIDTH-1];
        logic tile_accum_shift_inputs [0:TILE_HEIGHT-1];
        logic tile_accum_shift_outputs [0:TILE_HEIGHT-1];

        for (genvar k = 0; k < TILE_WIDTH; k++) begin
          // Assign vertical tile inputs
          assign tile_north_inputs[k] = (i == 0) ? north_inputs[j * TILE_WIDTH + k] : inter_south_outputs[i-1][j][k];
          assign tile_accum_inputs[k] = (i == 0) ? accum_inputs[j * TILE_WIDTH + k] : inter_accum_outputs[i-1][j][k];
          
          // Assign vertical tile outputs
          assign inter_south_outputs[i][j][k] = tile_south_outputs[k];
          assign inter_accum_outputs[i][j][k] = tile_accum_outputs[k];
        end

        for (genvar k = 0; k < TILE_HEIGHT; k++) begin
          // Assign horizontal tile inputs
          assign tile_west_inputs[k] = (j == 0) ? west_inputs[i * TILE_HEIGHT + k] : inter_east_outputs[i][j-1][k];
          assign tile_accum_shift_inputs[k] = (j == 0) ? accum_shift_in[i * TILE_HEIGHT + k] : inter_accum_shift[i][j-1][k];
          
          // Assign horizontal tile outputs
          assign inter_east_outputs[i][j][k] = tile_east_outputs[k];
          assign inter_accum_shift[i][j][k] = tile_accum_shift_outputs[k];
        end


        (* dont_touch = "yes" *) tile #(
          .BITS(BITS),
          .HEIGHT(TILE_HEIGHT),
          .WIDTH(TILE_WIDTH)
        ) u_tile (
          .clk(clk),
          .west_inputs(tile_west_inputs),
          .accum_inputs(tile_accum_inputs),
          .north_inputs(tile_north_inputs),
          .accum_shift_in(tile_accum_shift_inputs),
          .accum_shift_out(tile_accum_shift_outputs),
          .east_outputs(tile_east_outputs),
          .south_outputs(tile_south_outputs),
          .accum_outputs(tile_accum_outputs)
        );
      end
    end
  endgenerate

  genvar tile_x, pe_x;
  generate
    for (tile_x = 0; tile_x < NUM_TILES_WIDE; tile_x++) begin : output_col_tile
      for (pe_x = 0; pe_x < TILE_WIDTH; pe_x++) begin : output_pe
        assign south_outputs[tile_x*TILE_WIDTH + pe_x] = inter_south_outputs[NUM_TILES_TALL-1][tile_x][pe_x];
        assign accum_outputs[tile_x*TILE_WIDTH + pe_x] = inter_accum_outputs[NUM_TILES_TALL-1][tile_x][pe_x];
      end
    end
  endgenerate
  genvar tile_y, pe_y;
  generate
    for (tile_y = 0; tile_y < NUM_TILES_TALL; tile_y++) begin : output_row_tile
      for (pe_y = 0; pe_y < TILE_HEIGHT; pe_y++) begin : output_pe
        assign accum_shift_out[tile_y*TILE_HEIGHT + pe_y] = inter_accum_shift[tile_y][NUM_TILES_WIDE-1][pe_y];
        assign east_outputs[tile_y*TILE_HEIGHT + pe_y] = inter_east_outputs[tile_y][NUM_TILES_WIDE-1][pe_y];
      end
    end
  endgenerate
endmodule
