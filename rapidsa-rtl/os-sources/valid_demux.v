// Output register module for MM2S NOC channel.
// Pure Verilog so it can be instantiated directly in a Block Design.
//
// - Demuxes the DMA tvalid signal to m_valid_a / m_valid_b based on
//   active_channel, then registers the result.
// - Registers the FSM a_rd_en / b_rd_en / output_wr_en outputs.
// - Provides an inverted reset for the SA FSM.
//
// Registering the outputs at the BD boundary breaks long combinational paths
// from MM2S internal logic (sequencer state, FSM counters/comparators) through
// the long inter-module route to the edge buffer / drain tile control logic.

module valid_demux (
    input  wire clk,
    input  wire tvalid,
    input  wire active_channel,
    input  wire resetn,
    input  wire a_rd_en_in,
    input  wire b_rd_en_in,
    input  wire output_wr_en_in,

    output reg  m_valid_a,
    output reg  m_valid_b,
    output reg  a_rd_en,
    output reg  b_rd_en,
    output reg  output_wr_en,
    output wire reset
);

    assign reset = ~resetn;

    always @(posedge clk) begin
        m_valid_a    <= tvalid & ~active_channel;
        m_valid_b    <= tvalid &  active_channel;
        a_rd_en      <= a_rd_en_in;
        b_rd_en      <= b_rd_en_in;
        output_wr_en <= output_wr_en_in;
    end

endmodule
