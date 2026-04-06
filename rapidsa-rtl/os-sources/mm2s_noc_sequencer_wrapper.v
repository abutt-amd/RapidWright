`timescale 1ns / 1ps

// Pure Verilog wrapper for mm2s_noc_sequencer SystemVerilog module

module mm2s_noc_sequencer_wrapper (
    input  wire        clk,
    input  wire        rst_n,

    // External control interface
    input  wire        start,
    input  wire [31:0] src_addr_a,
    input  wire [22:0] transfer_length_a,
    input  wire [31:0] src_addr_b,
    input  wire [22:0] transfer_length_b,
    output wire        done,
    output wire        busy,
    output wire        error,

    // To DMA controller
    output wire        dma_start,
    output wire [31:0] dma_src_addr,
    output wire [22:0] dma_transfer_length,
    input  wire        dma_done,
    input  wire        dma_busy,
    input  wire        dma_error,

    // To DTU / demux
    output wire        active_channel,
    output wire        clr_en
);

    mm2s_noc_sequencer inst (
        .clk                (clk),
        .rst_n              (rst_n),

        .start              (start),
        .src_addr_a         (src_addr_a),
        .transfer_length_a  (transfer_length_a),
        .src_addr_b         (src_addr_b),
        .transfer_length_b  (transfer_length_b),
        .done               (done),
        .busy               (busy),
        .error              (error),

        .dma_start          (dma_start),
        .dma_src_addr       (dma_src_addr),
        .dma_transfer_length(dma_transfer_length),
        .dma_done           (dma_done),
        .dma_busy           (dma_busy),
        .dma_error          (dma_error),

        .active_channel     (active_channel),
        .clr_en      (clr_en)
    );

endmodule
