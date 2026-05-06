`timescale 1ns / 1ps

// Parallel ReLU tile: NUM_LANES signed DATA_BITS-bit lanes pass through a
// per-lane sign mux (negative -> 0) and a single pipeline register.
// A single shared valid bit gates the registered output.
module relu_tile #(
    parameter int NUM_LANES = 16,
    parameter int DATA_BITS = 8
)(
    input  logic                            clk,
    input  logic                            reset,

    input  logic [DATA_BITS-1:0]            data_in       [NUM_LANES],
    input  logic                            data_in_valid,

    output logic [DATA_BITS-1:0]            data_out      [NUM_LANES],
    output logic                            data_out_valid
);

    logic [DATA_BITS-1:0] data_out_reg [NUM_LANES];
    logic                 data_out_valid_reg;

    always_ff @(posedge clk) begin
        if (reset) begin
            data_out_valid_reg <= 1'b0;
            for (int i = 0; i < NUM_LANES; i++) begin
                data_out_reg[i] <= '0;
            end
        end else begin
            data_out_valid_reg <= data_in_valid;
            for (int i = 0; i < NUM_LANES; i++) begin
                // Signed ReLU: if MSB is set, the value is negative -> 0.
                data_out_reg[i] <= data_in[i][DATA_BITS-1] ? '0 : data_in[i];
            end
        end
    end

    assign data_out_valid = data_out_valid_reg;
    always_comb begin
        for (int i = 0; i < NUM_LANES; i++) begin
            data_out[i] = data_out_reg[i];
        end
    end

endmodule
