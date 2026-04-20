`timescale 1ns / 1ps

module dcu_fifo_tile_input #(
    parameter AXIS_WIDTH    = 128,
    parameter DATA_WIDTH    = 8,
    parameter TAG_WIDTH     = 8,
    parameter NUM_UNITS     = 4,
    parameter FIFO_PTR_SIZE = 6,
    parameter ID_WIDTH      = 8,
    parameter ID_OFFSET     = 0,
    parameter ELEM_REG_WIDTH = 8
)(
    input logic clk,
    input logic reset,

    // Wide tagged input (from previous tile or DTU)
    input  logic [NUM_LANES*DATA_WIDTH-1:0] s_data,
    input  logic [NUM_LANES*TAG_WIDTH-1:0]  s_tag,
    input  logic                            s_valid,
    output logic                            s_ready,

    // Wide tagged output (to next tile)
    output logic [NUM_LANES*DATA_WIDTH-1:0] m_data,
    output logic [NUM_LANES*TAG_WIDTH-1:0]  m_tag,
    output logic                            m_valid,
    input  logic                            m_ready,

    // FIFO read interface
    input logic                   rd_en,
    output logic                  rd_en_out,
    output logic [DATA_WIDTH-1:0] dout       [NUM_UNITS-1:0],
    output logic                  dout_valid [NUM_UNITS-1:0]
);

    localparam NUM_LANES  = AXIS_WIDTH / DATA_WIDTH;
    localparam LANE_WIDTH = DATA_WIDTH + TAG_WIDTH;
    localparam BUS_WIDTH  = NUM_LANES * LANE_WIDTH;

    // ID register initialized to ID_OFFSET, feeds back to itself
    (* dont_touch = "true" *) logic [ID_WIDTH-1:0] id_reg = ID_OFFSET;
    always_ff @(posedge clk) begin
        id_reg <= id_reg;
    end

    // =========================================================================
    // Stage 1: Input skid buffer
    // =========================================================================
    logic [NUM_LANES*DATA_WIDTH-1:0] s1_data;
    logic [NUM_LANES*TAG_WIDTH-1:0]  s1_tag;
    logic                            s1_valid;
    logic                            s1_ready;

    skid_buffer #(.WIDTH(BUS_WIDTH)) u_skid_in (
        .clk(clk),
        .rst_n(~reset),
        .s_data({s_data, s_tag}),
        .s_valid(s_valid),
        .s_ready(s_ready),
        .m_data({s1_data, s1_tag}),
        .m_valid(s1_valid),
        .m_ready(s1_ready)
    );

    // =========================================================================
    // Stage 2: Tag comparison (registered)
    // =========================================================================
    logic [NUM_UNITS-1:0] match_s2 [NUM_LANES-1:0]; // match_s2[lane][unit]
    logic [NUM_LANES*DATA_WIDTH-1:0] data_s2;
    logic [NUM_LANES*TAG_WIDTH-1:0]  tag_s2;
    logic                            valid_s2;
    logic                            ready_s2;

    // Unpack lane tags for comparison
    logic [TAG_WIDTH-1:0] lane_tag_s1 [NUM_LANES-1:0];
    logic [DATA_WIDTH-1:0] lane_data_s1 [NUM_LANES-1:0];
    genvar g;
    generate
        for (g = 0; g < NUM_LANES; g++) begin : gen_unpack_s1
            assign lane_tag_s1[g]  = s1_tag[g*TAG_WIDTH +: TAG_WIDTH];
            assign lane_data_s1[g] = s1_data[g*DATA_WIDTH +: DATA_WIDTH];
        end
    endgenerate

    // Stage 2 handshake: advance when stage 3 is ready or stage 2 is empty
    assign s1_ready = !valid_s2 || ready_s2;

    always_ff @(posedge clk) begin
        if (reset) begin
            valid_s2 <= 1'b0;
        end else if (s1_ready) begin
            valid_s2 <= s1_valid;
            data_s2  <= s1_data;
            tag_s2   <= s1_tag;
            for (int lane = 0; lane < NUM_LANES; lane++) begin
                for (int unit = 0; unit < NUM_UNITS; unit++) begin
                    match_s2[lane][unit] <= (lane_tag_s1[lane] == id_reg + ID_WIDTH'(unit));
                end
            end
        end
    end

    // =========================================================================
    // Stage 3: FIFO write from registered matches
    // =========================================================================
    logic [DATA_WIDTH-1:0] fifo_wdata [NUM_UNITS-1:0];
    logic                  fifo_wen   [NUM_UNITS-1:0];
    logic                  fifo_empty [NUM_UNITS-1:0];

    // Unpack stage 2 data for muxing
    logic [DATA_WIDTH-1:0] lane_data_s2 [NUM_LANES-1:0];
    generate
        for (g = 0; g < NUM_LANES; g++) begin : gen_unpack_s2
            assign lane_data_s2[g] = data_s2[g*DATA_WIDTH +: DATA_WIDTH];
        end
    endgenerate

    // For each unit, find the first matching lane and write its data
    always_comb begin
        for (int unit = 0; unit < NUM_UNITS; unit++) begin
            fifo_wen[unit]   = 1'b0;
            fifo_wdata[unit] = '0;
            for (int lane = NUM_LANES - 1; lane >= 0; lane--) begin
                if (match_s2[lane][unit]) begin
                    fifo_wen[unit]   = valid_s2;
                    fifo_wdata[unit] = lane_data_s2[lane];
                end
            end
        end
    end

    // Stage 3 advances when stage 4 (output skid) is ready
    logic ready_s3;
    assign ready_s2 = ready_s3;

    // =========================================================================
    // Stage 4: Output skid buffer (forward to next tile)
    // =========================================================================
    logic [NUM_LANES*DATA_WIDTH-1:0] out_data;
    logic [NUM_LANES*TAG_WIDTH-1:0]  out_tag;

    // Register data for output skid
    always_ff @(posedge clk) begin
        if (reset) begin
            out_data <= '0;
            out_tag  <= '0;
        end else if (ready_s2) begin
            out_data <= data_s2;
            out_tag  <= tag_s2;
        end
    end

    logic out_valid;
    always_ff @(posedge clk) begin
        if (reset)
            out_valid <= 1'b0;
        else if (ready_s3)
            out_valid <= valid_s2;
    end

    skid_buffer #(.WIDTH(BUS_WIDTH)) u_skid_out (
        .clk(clk),
        .rst_n(~reset),
        .s_data({out_data, out_tag}),
        .s_valid(out_valid),
        .s_ready(ready_s3),
        .m_data({m_data, m_tag}),
        .m_valid(m_valid),
        .m_ready(m_ready)
    );

    // =========================================================================
    // rd_en pipeline: registered between each unit
    // =========================================================================
    logic rd_en_pipe [NUM_UNITS:0];
    always_ff @(posedge clk) begin
        if (reset)
            rd_en_pipe[0] <= 1'b0;
        else
            rd_en_pipe[0] <= rd_en;
    end

    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_rd_en_pipe
            always_ff @(posedge clk) begin
                if (reset)
                    rd_en_pipe[g+1] <= 1'b0;
                else
                    rd_en_pipe[g+1] <= rd_en_pipe[g];
            end
        end
    endgenerate

    assign rd_en_out = rd_en_pipe[NUM_UNITS];

    logic rd_en_arr [NUM_UNITS-1:0];
    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_rd_en_arr
            assign rd_en_arr[g] = rd_en_pipe[g];
        end
    endgenerate

    // =========================================================================
    // FIFO tile
    // =========================================================================
    logic [FIFO_PTR_SIZE:0] fifo_count [NUM_UNITS-1:0];
    logic                   fifo_full_internal [NUM_UNITS-1:0];

    fifo_tile #(
        .DWIDTH(DATA_WIDTH),
        .PTR_SIZE(FIFO_PTR_SIZE),
        .NUM_FIFOS(NUM_UNITS)
    ) u_fifo_tile (
        .reset(reset),
        .clk(clk),
        .wr_en(fifo_wen),
        .din(fifo_wdata),
        .rd_en(rd_en_arr),
        .dout(dout),
        .count(fifo_count),
        .empty(fifo_empty),
        .full(fifo_full_internal)
    );

    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_dout_valid
            always_ff @(posedge clk) begin
                if (reset) begin
                    dout_valid[g] <= 1'b0;
                end else begin
                    dout_valid[g] <= ~fifo_empty[g] && rd_en_arr[g];
                end
            end
        end
    endgenerate

endmodule
