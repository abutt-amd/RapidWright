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


module tile
    #(parameter WIDTH = 4, 
      parameter HEIGHT = 4, 
      parameter BITS = 8
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


    logic [BITS-1:0] temp_west [0:HEIGHT-1][0:WIDTH-1];
    logic [BITS-1:0] temp_north [0:HEIGHT-1][0:WIDTH-1];
    logic [BITS-1:0] temp_accum_ins [0:HEIGHT-1][0:WIDTH-1];
    logic [BITS-1:0] temp_accum_outs [0:HEIGHT-1][0:WIDTH-1];
    logic [BITS-1:0] temp_east [0:HEIGHT-1][0:WIDTH-1];
    logic [BITS-1:0] temp_south [0:HEIGHT-1][0:WIDTH-1];
    logic temp_accum_shift_in [0:HEIGHT-1][0:WIDTH-1];
    logic temp_accum_shift_out [0:HEIGHT-1][0:WIDTH-1];
    
    assign south_outputs = temp_south[HEIGHT-1];
    assign accum_outputs = temp_accum_outs[HEIGHT-1];
    
    genvar i, j;
    generate
        for (j = 0; j < WIDTH; j++) begin : x
            for (i = 0; i < HEIGHT; i++) begin : y

                if (j == 0) begin
                  assign temp_west[i][j] = west_inputs[i];
                  assign temp_accum_shift_in[i][j] = accum_shift_in[i];
                end else begin 
                  assign temp_west[i][j] = temp_east[i][j-1];
                  assign temp_accum_shift_in[i][j] = temp_accum_shift_out[i][j-1];
                end

                if (i == 0) begin
                  assign temp_north[i][j] = north_inputs[j];
                  assign temp_accum_ins[i][j] = accum_inputs[j];
                end else begin
                  assign temp_north[i][j] = temp_south[i-1][j];
                  assign temp_accum_ins[i][j] = temp_accum_outs[i-1][j];
                end
                
                if (j == WIDTH - 1) begin 
                  assign east_outputs[i] = temp_east[i][j];
                  assign accum_shift_out[i] = temp_accum_shift_out[i][j];
                end
                
                pe #(.BITS(BITS)) u_pe (
                    .west(temp_west[i][j]),
                    .north(temp_north[i][j]),
                    .accum_in(temp_accum_ins[i][j]),
                    .accum_shift_in(temp_accum_shift_in[i][j]),
                    .clk(clk),
                    .accum_shift_out(temp_accum_shift_out[i][j]),
                    .east(temp_east[i][j]),
                    .south(temp_south[i][j]),
                    .accum_out(temp_accum_outs[i][j])
                );

            end
        end
    endgenerate

endmodule
