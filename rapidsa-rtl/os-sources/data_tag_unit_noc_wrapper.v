`timescale 1ns / 1ps

module data_tag_unit_noc_wrapper #(
    parameter MATRIX_HEIGHT = 32,
    parameter MATRIX_WIDTH = 32,
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH = 8
)(
    input wire                  clk,
    input wire                  rst_n,

    input wire                  tag_source_in,
    input wire                  clr_en,

    input wire [DATA_WIDTH-1:0] s_axis_tdata,
    input wire                  s_axis_tvalid,
    output wire                 s_axis_tready,

    output wire [DATA_WIDTH-1:0] m_data,
    output wire [TAG_WIDTH-1:0]  m_tag,
    output wire                  m_valid,
    input wire                   m_ready
);

    data_tag_unit_noc #(
        .MATRIX_HEIGHT(MATRIX_HEIGHT),
        .MATRIX_WIDTH(MATRIX_WIDTH),
        .DATA_WIDTH(DATA_WIDTH),
        .TAG_WIDTH(TAG_WIDTH)
    ) inst (
        .clk(clk),
        .rst_n(rst_n),
        .tag_source_in(tag_source_in),
        .clr_en(clr_en),
        .s_axis_tdata(s_axis_tdata),
        .s_axis_tvalid(s_axis_tvalid),
        .s_axis_tready(s_axis_tready),
        .m_data(m_data),
        .m_tag(m_tag),
        .m_valid(m_valid),
        .m_ready(m_ready)
    );

endmodule
