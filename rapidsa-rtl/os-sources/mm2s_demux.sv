`timescale 1ns / 1ps

// Combinational demux steering DTU output to port A or port B
// based on the active_channel select signal.

module mm2s_demux #(
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH  = 8
)(
    input  logic clk,
    input  logic rst_n,

    input  logic sel,   // 0 = port A, 1 = port B

    // From DTU (slave side)
    input  logic [DATA_WIDTH-1:0] s_data,
    input  logic [TAG_WIDTH-1:0]  s_tag,
    input  logic                  s_valid,
    output logic                  s_ready,

    // Port A (master side)
    output logic [DATA_WIDTH-1:0] m_data_a,
    output logic [TAG_WIDTH-1:0]  m_tag_a,
    output logic                  m_valid_a,
    input  logic                  m_ready_a,

    // Port B (master side)
    output logic [DATA_WIDTH-1:0] m_data_b,
    output logic [TAG_WIDTH-1:0]  m_tag_b,
    output logic                  m_valid_b,
    input  logic                  m_ready_b
);

    // Combinational demux
    logic [DATA_WIDTH-1:0] demux_data_a, demux_data_b;
    logic [TAG_WIDTH-1:0]  demux_tag_a,  demux_tag_b;
    logic                  demux_valid_a, demux_valid_b;
    logic                  demux_ready_a, demux_ready_b;

    assign demux_data_a  = s_data;
    assign demux_tag_a   = s_tag;
    assign demux_valid_a = ~sel & s_valid;

    assign demux_data_b  = s_data;
    assign demux_tag_b   = s_tag;
    assign demux_valid_b = sel & s_valid;

    assign s_ready = sel ? demux_ready_b : demux_ready_a;

    // Output register slices
    skid_buffer #(.WIDTH(DATA_WIDTH + TAG_WIDTH)) u_skid_a (
        .clk(clk),
        .rst_n(rst_n),
        .s_data({demux_data_a, demux_tag_a}),
        .s_valid(demux_valid_a),
        .s_ready(demux_ready_a),
        .m_data({m_data_a, m_tag_a}),
        .m_valid(m_valid_a),
        .m_ready(m_ready_a)
    );

    skid_buffer #(.WIDTH(DATA_WIDTH + TAG_WIDTH)) u_skid_b (
        .clk(clk),
        .rst_n(rst_n),
        .s_data({demux_data_b, demux_tag_b}),
        .s_valid(demux_valid_b),
        .s_ready(demux_ready_b),
        .m_data({m_data_b, m_tag_b}),
        .m_valid(m_valid_b),
        .m_ready(m_ready_b)
    );

endmodule
