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
    parameter BITS = 32
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

    floating_point_0 fp_uma_0 (
        .aclk(clk),
        .s_axis_a_tdata(a),
        .s_axis_a_tvalid(1'b1),
        .s_axis_b_tdata(b),
        .s_axis_b_tvalid(1'b1),
        .s_axis_c_tdata(c),
        .s_axis_c_tvalid(1'b1),
        .m_axis_result_tdata(out),
        .m_axis_result_tvalid()
    );

    
    always_ff @(posedge clk) begin
//        a_tmp <= a;
//        b_tmp <= b;
//        c_tmp <= c;
        //out <= out_tmp;
    end

endmodule

module pe #(
    parameter BITS = 32
)(    
    input logic [BITS - 1:0] west,
    input logic [BITS - 1:0] north,
    input logic [BITS - 1:0] weight_in,
    input logic reset,
    input logic weight_shift_in,
    input logic clk,
    output logic weight_shift_out,
    output logic [BITS - 1:0] east,
    output logic [BITS - 1:0] south,
    output logic [BITS - 1:0] weight_out
);

    logic [BITS - 1:0] weight_reg;
    logic [BITS - 1:0] a;
    logic [BITS - 1:0] b;
    logic [BITS - 1:0] c;
    logic [BITS - 1:0] out;
    
    localparam FMA_LATENCY = 1;
    
    multiply_add #(
        .BITS(BITS)
    ) u0 (
        .clk(clk),
        .a(a),
        .b(b),
        .c(c),
        .out(out)
    );
    
    always_comb begin
        a = west;
        b = weight_reg;
        c = north;
        south = out; 
    end

    logic [BITS - 1:0] west_tmp[0:FMA_LATENCY - 1];
    
    integer i;    
    always_ff @(posedge clk) begin
        if (weight_shift_in) begin
            weight_reg <= weight_in;
        end
        weight_shift_out <= weight_shift_in;
        west_tmp[0] <= west;
        for (i = 0; i < FMA_LATENCY - 1; i++) begin
            west_tmp[i+1] <= west_tmp[i];
        end
    end

    assign east = west_tmp[FMA_LATENCY - 1];  
    assign weight_out = weight_reg;
endmodule