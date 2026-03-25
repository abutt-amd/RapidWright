`timescale 1ns / 1ps

module drain_l2_tile #(
    parameter DATA_WIDTH = 8,
    parameter NUM_L2_UNITS = 4,              // Number of L2 units in this tile
    parameter COLUMN_ELEMENTS = 8,           // Elements per column FIFO to drain
    parameter EXTERNAL_UPSTREAM_ELEMENTS = 1, // Elements expected from external upstream (non-zero to preserve forwarding logic)
    parameter FIFO_PTR_SIZE = 4,             // FIFO depth = 2^PTR_SIZE
    parameter ELEM_REG_WIDTH = 8              // Width of col_elem_reg and ext_ups_elem_reg registers
)(
    input  logic clk,
    input  logic reset,

    // FIFO write interfaces (directly to external logic - one per L2 unit)
    input  logic [NUM_L2_UNITS-1:0]                 fifo_wr_en,
    input  logic [NUM_L2_UNITS-1:0][DATA_WIDTH-1:0] fifo_din,

    // Downstream AXI-Stream (output from tile, toward memory)
    output logic [DATA_WIDTH-1:0] m_axis_downstream_tdata,
    output logic                  m_axis_downstream_tvalid,
    input  logic                  m_axis_downstream_tready,

    // Upstream AXI-Stream (input to tile from external upstream)
    input  logic [DATA_WIDTH-1:0] s_axis_upstream_tdata,
    input  logic                  s_axis_upstream_tvalid,
    output logic                  s_axis_upstream_tready
);

    // Column elements register, initialized to COLUMN_ELEMENTS, feeds back to itself
    (* dont_touch = "true" *) logic [ELEM_REG_WIDTH-1:0] col_elem_reg = ELEM_REG_WIDTH'(COLUMN_ELEMENTS);
    always_ff @(posedge clk) begin
        col_elem_reg <= col_elem_reg;
    end

    // External upstream elements register, initialized to EXTERNAL_UPSTREAM_ELEMENTS, feeds back to itself
    (* dont_touch = "true" *) logic [ELEM_REG_WIDTH-1:0] ext_ups_elem_reg = ELEM_REG_WIDTH'(EXTERNAL_UPSTREAM_ELEMENTS);
    always_ff @(posedge clk) begin
        ext_ups_elem_reg <= ext_ups_elem_reg;
    end

    // Calculate UPSTREAM_ELEMENTS for each L2 unit
    // L2[i] forwards: (NUM_L2_UNITS - 1 - i) * COLUMN_ELEMENTS + EXTERNAL_UPSTREAM_ELEMENTS
    // L2[NUM_L2_UNITS-1] (last in chain): forwards EXTERNAL_UPSTREAM_ELEMENTS
    // L2[0] (first, outputs to downstream): forwards all upstream data
    function automatic int calc_upstream_elements(input int idx);
        return (NUM_L2_UNITS - 1 - idx) * COLUMN_ELEMENTS + EXTERNAL_UPSTREAM_ELEMENTS;
    endfunction

    // Internal signals for FIFO interfaces
    logic [DATA_WIDTH-1:0] fifo_dout  [NUM_L2_UNITS-1:0];
    logic                  fifo_empty [NUM_L2_UNITS-1:0];
    logic                  fifo_rd_en [NUM_L2_UNITS-1:0];

    // Internal signals for L2 chain connections
    logic [DATA_WIDTH-1:0] l2_downstream_tdata  [NUM_L2_UNITS-1:0];
    logic                  l2_downstream_tvalid [NUM_L2_UNITS-1:0];
    logic                  l2_downstream_tready [NUM_L2_UNITS-1:0];
    logic [DATA_WIDTH-1:0] l2_upstream_tdata    [NUM_L2_UNITS-1:0];
    logic                  l2_upstream_tvalid   [NUM_L2_UNITS-1:0];
    logic                  l2_upstream_tready   [NUM_L2_UNITS-1:0];

    // Generate FIFOs and L2 units
    genvar i;
    generate
        for (i = 0; i < NUM_L2_UNITS; i++) begin : gen_l2_chain

            // Instantiate FIFO for this L2 unit
            fifo #(
                .DWIDTH(DATA_WIDTH),
                .PTR_SIZE(FIFO_PTR_SIZE)
            ) u_fifo (
                .clk(clk),
                .reset(reset),
                .wr_en(fifo_wr_en[i]),
                .din(fifo_din[i]),
                .rd_en(fifo_rd_en[i]),
                .dout(fifo_dout[i]),
                .count(),  // Not used externally
                .empty(fifo_empty[i]),
                .full()
            );

            // Instantiate L2 drain module
            drain_l2_module #(
                .DATA_WIDTH(DATA_WIDTH),
                .COLUMN_ELEMENTS(COLUMN_ELEMENTS),
                .UPSTREAM_ELEMENTS(calc_upstream_elements(i))
            ) u_drain_l2 (
                .clk(clk),
                .rst_n(~reset),
                .col_fifo_data(fifo_dout[i]),
                .col_fifo_empty(fifo_empty[i]),
                .col_fifo_rd_en(fifo_rd_en[i]),
                .m_axis_downstream_tdata(l2_downstream_tdata[i]),
                .m_axis_downstream_tvalid(l2_downstream_tvalid[i]),
                .m_axis_downstream_tready(l2_downstream_tready[i]),
                .s_axis_upstream_tdata(l2_upstream_tdata[i]),
                .s_axis_upstream_tvalid(l2_upstream_tvalid[i]),
                .s_axis_upstream_tready(l2_upstream_tready[i])
            );

        end
    endgenerate

    // Chain connections:
    // L2[0] downstream -> external downstream
    // L2[i] downstream -> L2[i-1] upstream (for i > 0)
    // L2[NUM_L2_UNITS-1] upstream <- external upstream

    // Skid buffer on downstream output to register tdata/tvalid out and break tready chain
    skid_buffer #(
        .WIDTH(DATA_WIDTH)
    ) u_downstream_skid (
        .clk(clk),
        .rst_n(~reset),
        .s_data(l2_downstream_tdata[0]),
        .s_valid(l2_downstream_tvalid[0]),
        .s_ready(l2_downstream_tready[0]),
        .m_data(m_axis_downstream_tdata),
        .m_valid(m_axis_downstream_tvalid),
        .m_ready(m_axis_downstream_tready)
    );

    // Skid buffer on upstream input to register tready going back to upstream tile
    skid_buffer #(
        .WIDTH(DATA_WIDTH)
    ) u_upstream_skid (
        .clk(clk),
        .rst_n(~reset),
        .s_data(s_axis_upstream_tdata),
        .s_valid(s_axis_upstream_tvalid),
        .s_ready(s_axis_upstream_tready),
        .m_data(l2_upstream_tdata[NUM_L2_UNITS-1]),
        .m_valid(l2_upstream_tvalid[NUM_L2_UNITS-1]),
        .m_ready(l2_upstream_tready[NUM_L2_UNITS-1])
    );

    // Internal chain connections
    generate
        for (i = 0; i < NUM_L2_UNITS - 1; i++) begin : gen_chain_connections
            // L2[i+1] downstream connects to L2[i] upstream
            assign l2_upstream_tdata[i]      = l2_downstream_tdata[i+1];
            assign l2_upstream_tvalid[i]     = l2_downstream_tvalid[i+1];
            assign l2_downstream_tready[i+1] = l2_upstream_tready[i];
        end
    endgenerate

endmodule
