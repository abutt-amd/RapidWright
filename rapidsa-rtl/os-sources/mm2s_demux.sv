`timescale 1ns / 1ps

// Combinational demux steering DTU output to port A or port B
// based on the active_channel select signal.

module mm2s_demux #(
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH  = 8
)(
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

    assign m_data_a  = s_data;
    assign m_tag_a   = s_tag;
    assign m_valid_a = ~sel & s_valid;

    assign m_data_b  = s_data;
    assign m_tag_b   = s_tag;
    assign m_valid_b = sel & s_valid;

    assign s_ready = sel ? m_ready_b : m_ready_a;

endmodule
