`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 06/19/2025 09:25:29 PM
// Design Name: 
// Module Name: processing_element
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

module multiply_add #(
    parameter BITS = 8
) (
    input logic clk,
    input logic [BITS - 1:0] a,
    input logic [BITS - 1:0] b,
    input logic [BITS - 1:0] c,
    output logic [BITS - 1:0] out
);

    logic [BITS-1:0] a_tmp;
    logic [BITS-1:0] b_tmp;
    logic [BITS-1:0] c_tmp;
    logic [BITS-1:0] out_tmp;

    xbip_multadd_0 int8_ma_0 (
        .CLK(clk),
        .A(a),
        .B(b),
        .C(c),
        .P(out),
        .SUBTRACT(0),
        .CE(1),
        .SCLR(0)
    );

    
    always_ff @(posedge clk) begin
//        a_tmp <= a;
//        b_tmp <= b;
//        c_tmp <= c;
        //out <= out_tmp;
    end

endmodule

module pe #(
    parameter BITS = 8
)(    
    input logic [BITS - 1:0] west,
    input logic [BITS - 1:0] north,
    input logic [BITS - 1:0] accum_in,
    input logic accum_shift_in,
    input logic clk,
    output logic accum_shift_out,
    output logic [BITS - 1:0] east,
    output logic [BITS - 1:0] south,
    output logic [BITS - 1:0] accum_out
);

    logic [BITS - 1:0] a;
    logic [BITS - 1:0] b;
    logic [BITS - 1:0] c;
    logic [BITS - 1:0] accum_reg;
    logic [BITS - 1:0] out;
    
    multiply_add #(
        .BITS(BITS)
    ) u0 (
        .clk(clk),
        .a(a),
        .b(b),
        .c(c),
        .out(out)
    );
    
    logic [BITS - 1:0] north_tmp;
    logic [BITS - 1:0] west_tmp;
    
    always_comb begin
        a = west_tmp;
        b = north_tmp;
        c = accum_reg;
    end

    logic [BITS - 1:0] activation_tmp;
    
    logic [BITS - 1:0] weight_tmp;
    
    integer i;    
    always_ff @(posedge clk) begin
        if (accum_shift_in) begin
            accum_reg <= accum_in;
        end else begin
            accum_reg <= out;
        end
        accum_shift_out <= accum_shift_in;
        west_tmp <= west;
        activation_tmp <= west_tmp;
        north_tmp <= north;
        weight_tmp <= north_tmp;
    end

    assign east = activation_tmp;
    assign south = weight_tmp;
    assign accum_out = accum_reg;
endmodule