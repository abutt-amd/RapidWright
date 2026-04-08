`timescale 1ns / 1ps

module daisy_chain_loader #(
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH = 8,
    parameter ID_WIDTH = 8
)(
    input logic clk,
    input logic rst_n,

    input logic [ID_WIDTH-1:0]  id,

    input logic [DATA_WIDTH-1:0] s_data,
    input logic [TAG_WIDTH-1:0]  s_tag,
    input logic                  s_valid,
    output logic                 s_ready,

    output logic [DATA_WIDTH-1:0] m_data,
    output logic [TAG_WIDTH-1:0]  m_tag,
    output logic                  m_valid,
    input logic                   m_ready,

    output logic [DATA_WIDTH-1:0] fifo_wdata,
    output logic                  fifo_wen,
    input logic                   fifo_full
);

    logic tag_match;
    assign tag_match = (s_tag == id);

    logic [DATA_WIDTH-1:0] fwd_data_reg;
    logic [TAG_WIDTH-1:0]  fwd_tag_reg;
    logic                  fwd_valid_reg;

    logic fwd_handshake;
    assign fwd_handshake = fwd_valid_reg && m_ready;

    logic s_handshake;
    assign s_handshake = s_valid && s_ready;

    assign s_ready = !fwd_valid_reg || m_ready;

    assign fifo_wdata = s_data;
    assign fifo_wen = s_handshake && tag_match;

    assign m_data = fwd_data_reg;
    assign m_tag = fwd_tag_reg;
    assign m_valid = fwd_valid_reg;

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            fwd_valid_reg <= 1'b0;
            fwd_data_reg <= '0;
            fwd_tag_reg <= '0;
        end else begin
            if (s_handshake && !tag_match) begin
                fwd_data_reg <= s_data;
                fwd_tag_reg <= s_tag;
                fwd_valid_reg <= 1'b1;
            end else if (fwd_handshake) begin
                fwd_valid_reg <= 1'b0;
            end
        end
    end

endmodule
