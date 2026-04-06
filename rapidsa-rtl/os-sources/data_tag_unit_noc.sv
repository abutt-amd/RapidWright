`timescale 1ns / 1ps

// Data tag unit variant for NOC channel with external tag source and clear.
// Unlike the base data_tag_unit, tag_source is driven by an external port
// (from the sequencer) rather than a dont_touch register, and clr_en allows
// resetting the row/col counters between sequential transfers.

module data_tag_unit_noc #(
    parameter MATRIX_HEIGHT = 32,
    parameter MATRIX_WIDTH = 32,
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH = 8
)(
    input logic                  clk,
    input logic                  rst_n,

    input logic                  tag_source_in,   // 0 = ROW, 1 = COL
    input logic                  clr_en,          // clears row/col counters

    input logic [DATA_WIDTH-1:0] s_axis_tdata,
    input logic                  s_axis_tvalid,
    output logic                 s_axis_tready,

    output logic [DATA_WIDTH-1:0] m_data,
    output logic [TAG_WIDTH-1:0]  m_tag,
    output logic                  m_valid,
    input logic                   m_ready
);

    localparam DIM_WIDTH = TAG_WIDTH;

    logic [DIM_WIDTH-1:0] col;
    logic [DIM_WIDTH-1:0] row;

    (* dont_touch = "true" *)
    logic [DIM_WIDTH-1:0] matrix_height_reg;

    (* dont_touch = "true" *)
    logic [DIM_WIDTH-1:0] matrix_width_reg;

    initial matrix_height_reg = MATRIX_HEIGHT[DIM_WIDTH-1:0];
    initial matrix_width_reg = MATRIX_WIDTH[DIM_WIDTH-1:0];

    always_ff @(posedge clk) begin
        matrix_height_reg <= matrix_height_reg;
        matrix_width_reg <= matrix_width_reg;
    end

    logic handshake;
    assign handshake = m_valid && m_ready;

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            row <= '0;
            col <= '0;
        end else if (clr_en) begin
            row <= '0;
            col <= '0;
        end else begin
            if (handshake) begin
                if (col == matrix_width_reg - 1) begin
                    col <= '0;
                    if (row == matrix_height_reg - 1) begin
                        row <= '0;
                    end else begin
                        row <= row + 1;
                    end
                end else begin
                    col <= col + 1;
                end
            end
        end
    end

    assign m_data = s_axis_tdata;
    assign m_tag = tag_source_in ? col : row;
    assign m_valid = s_axis_tvalid;
    assign s_axis_tready = m_ready;

endmodule
