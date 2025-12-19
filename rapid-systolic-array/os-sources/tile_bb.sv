`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 09/09/2025 02:51:58 PM
// Design Name: 
// Module Name: tile
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


(* black_box *) module tile #(
  parameter WIDTH = 4, 
  parameter HEIGHT = 4, 
  parameter BITS = 32
)(
    input logic [BITS-1:0] west_inputs [0:HEIGHT-1],
    input logic [BITS-1:0] accum_inputs [0:WIDTH-1],
    input logic [BITS-1:0] north_inputs [0:WIDTH-1],
    input logic clk,
    input logic accum_shift_in [0:HEIGHT-1],
    
    output logic accum_shift_out [0:HEIGHT-1],
    output logic [BITS-1:0] east_outputs [0:HEIGHT-1],
    output logic [BITS-1:0] south_outputs [0:WIDTH-1],
    output logic [BITS-1:0] accum_outputs [0:WIDTH-1]
);

endmodule
