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

module macc  # (
                parameter SIZEIN = 16, SIZEOUT = 40
               )
               (
                input clk, ce, sload,
                input signed  [SIZEIN-1:0]  a, b,
                output signed [SIZEOUT-1:0] accum_out
               );

   // Declare registers for intermediate values
reg signed [SIZEIN-1:0]  a_reg, b_reg;
reg                      sload_reg;
reg signed [2*SIZEIN:0]  mult_reg;
reg signed [SIZEOUT-1:0] adder_out, old_result;

always_comb
begin
  if (sload_reg)
    old_result <= 0;
  else
   // 'sload' is now active (=low) and opens the accumulation loop.
   // The accumulator takes the next multiplier output in
   // the same cycle.
    old_result <= adder_out;
end

always @(posedge clk)
 if (ce)
   begin
      a_reg <= a;
      b_reg <= b;
      mult_reg <= a_reg * b_reg;
      sload_reg <= sload;
      // Store accumulation result into a register
      adder_out <= old_result + mult_reg;
   end

// Output accumulation result
assign accum_out = adder_out;

endmodule

(* use_dsp = "yes" *) module macc_dsp  # (
                parameter SIZEIN = 8, SIZEOUT = 16
               )
               (
                input clk, ce, sload,
                input signed  [SIZEIN-1:0]  accum_in,
                input signed  [SIZEIN-1:0]  a, b,
                output signed [SIZEOUT-1:0] accum_out
               );

   // Declare registers for intermediate values
   // Initialize control/multiply path to avoid X during SHIFT_OUT
   // adder_out is intentionally not initialized - SHIFT_OUT loads proper values via accum_in
reg signed [SIZEIN-1:0]  a_reg = '0;
reg signed [SIZEIN-1:0]  b_reg = '0;
reg                      sload_reg = 1'b1;  // Start in load mode so accum_in flows through
reg signed [SIZEIN-1:0]  accum_in_reg = '0;
reg signed [2*SIZEIN:0]  mult_reg = '0;
reg signed [SIZEOUT-1:0] adder_out = '0;
reg signed [SIZEOUT-1:0] old_result;

always_comb
begin
  if (sload_reg)
    old_result = accum_in_reg;
  else
   // 'sload' is now active (=low) and opens the accumulation loop.
   // The accumulator takes the next multiplier output in
   // the same cycle.
    old_result = adder_out;
end

always @(posedge clk)
 if (ce)
   begin
      a_reg <= a;
      b_reg <= b;
      mult_reg <= a_reg * b_reg;
      sload_reg <= sload;
      accum_in_reg <= accum_in;
      // Store accumulation result into a register
      adder_out <= old_result + mult_reg;
   end

// Output accumulation result
assign accum_out = adder_out;

endmodule

module multiply_accum #(
    parameter BITS = 8
) (
    input logic clk,
    input logic [BITS - 1:0] accum_in,
    input logic sload,
    
    input logic [BITS - 1:0] a,
    input logic a_val,
    
    input logic [BITS - 1:0] b,
    input logic b_val,
    
    output logic [BITS - 1:0] accum_out
);

    logic [BITS-1:0] a_tmp;
    logic [BITS-1:0] b_tmp;
    logic val;

    macc_dsp #(
        .SIZEIN(BITS),
        .SIZEOUT(BITS*2)
    ) int8_ma_0 (
        .clk(clk),
        .ce(1'b1),
        .sload(sload),
        .accum_in(accum_in),
        .a(a_tmp),
        .b(b_tmp),
        .accum_out(accum_out)
    );

    
    always_comb begin
      val = a_val & b_val;
      a_tmp = val ? a : 0;
      b_tmp = val ? b : 0;
    end

endmodule

module pe #(
    parameter BITS = 8
)(
    input logic [BITS - 1:0] west,
    input logic west_valid,

    input logic [BITS - 1:0] north,
    input logic north_valid,

    input logic [BITS - 1:0] accum_in,

    input logic accum_shift,
    input logic clk,

    output logic [BITS - 1:0] east,
    output logic east_valid,

    output logic [BITS - 1:0] south,
    output logic south_valid,

    output logic [BITS - 1:0] accum_out = '0
);

    logic [BITS - 1:0] a;
    logic a_val;
    logic [BITS - 1:0] b;
    logic b_val;
    logic [BITS - 1:0] accum_in_tmp = '0;
    logic accum_shift_tmp = 1'b1;
    logic [BITS - 1:0] accum_out_tmp;

    multiply_accum #(
        .BITS(BITS)
    ) u0 (
        .clk(clk),
        .accum_in(accum_shift_reg),
        .sload(shift_done),
        .a(a),
        .a_val(a_val),
        .b(b),
        .b_val(b_val),
        .accum_out(accum_out_tmp)
    );

    logic [BITS - 1:0] activation_tmp = '0;
    logic activation_val_tmp = 1'b0;
    logic [BITS - 1:0] weight_tmp = '0;
    logic weight_val_tmp = 1'b0;

    logic [BITS - 1:0] accum_shift_reg = '0;
    logic accum_shift_prev = 1'b0;
    logic first_shift_cycle;
    logic shift_done;

    always_comb begin
        a = activation_tmp;
        a_val = activation_val_tmp;
        b = weight_tmp;
        b_val = weight_val_tmp;
        first_shift_cycle = accum_shift_tmp && !accum_shift_prev;
        shift_done = !accum_shift_tmp && accum_shift_prev;
        accum_out = accum_shift_reg;
    end

    always_ff @(posedge clk) begin
        activation_tmp <= west;
        activation_val_tmp <= west_valid;

        weight_tmp <= north;
        weight_val_tmp <= north_valid;

        accum_in_tmp <= accum_in;
        accum_shift_tmp <= accum_shift;
        accum_shift_prev <= accum_shift_tmp;

        if (first_shift_cycle) begin
            accum_shift_reg <= accum_out_tmp;
        end else if (accum_shift_tmp) begin
            accum_shift_reg <= accum_in;
        end
    end

    assign east = activation_tmp;
    assign east_valid = activation_val_tmp;

    assign south = weight_tmp;
    assign south_valid = weight_val_tmp;
endmodule