// Edge Buffer Tile Wrapper — Pure Verilog wrapper for SystemVerilog module

module edge_buffer_tile_wrapper #(
    parameter AXIS_WIDTH    = 128,
    parameter DATA_WIDTH    = 8,
    parameter NUM_UNITS     = 4,
    parameter FIFO_PTR_SIZE = 6
) (
    input  wire                    clk,
    input  wire                    reset,

    // Broadcast data input
    input  wire [AXIS_WIDTH-1:0]   s_data,
    input  wire                    s_valid,

    // Forwarding output
    output wire [AXIS_WIDTH-1:0]   m_data,
    output wire                    m_valid,

    // Word index sideband
    input  wire [3:0]              s_word_index,
    output wire [3:0]              m_word_index,

    // FIFO read interface
    input  wire                    rd_en,
    output wire                    rd_en_out,
    output wire [DATA_WIDTH-1:0]   dout_0,
    output wire [DATA_WIDTH-1:0]   dout_1,
    output wire [DATA_WIDTH-1:0]   dout_2,
    output wire [DATA_WIDTH-1:0]   dout_3,
    output wire                    dout_valid_0,
    output wire                    dout_valid_1,
    output wire                    dout_valid_2,
    output wire                    dout_valid_3
);

    wire [DATA_WIDTH-1:0] dout [NUM_UNITS-1:0];
    wire                  dout_valid [NUM_UNITS-1:0];

    edge_buffer_tile #(
        .AXIS_WIDTH(AXIS_WIDTH),
        .DATA_WIDTH(DATA_WIDTH),
        .NUM_UNITS(NUM_UNITS),
        .FIFO_PTR_SIZE(FIFO_PTR_SIZE)
    ) inst (
        .clk            (clk),
        .reset          (reset),
        .s_data         (s_data),
        .s_valid        (s_valid),
        .m_data         (m_data),
        .m_valid        (m_valid),
        .s_word_index   (s_word_index),
        .m_word_index   (m_word_index),
        .rd_en          (rd_en),
        .rd_en_out      (rd_en_out),
        .dout           (dout),
        .dout_valid     (dout_valid)
    );

    assign dout_0 = dout[0];
    assign dout_1 = dout[1];
    assign dout_2 = dout[2];
    assign dout_3 = dout[3];
    assign dout_valid_0 = dout_valid[0];
    assign dout_valid_1 = dout_valid[1];
    assign dout_valid_2 = dout_valid[2];
    assign dout_valid_3 = dout_valid[3];

endmodule
