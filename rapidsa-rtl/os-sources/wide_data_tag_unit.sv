`timescale 1ns / 1ps

// Wide Data Tag Unit - 128-bit (16-byte) input with parallel tag generation
// Uses a byte offset counter (simple +16 adder in feedback loop).
// Row/col for each lane derived combinationally from offset using parallel subtraction.

module wide_data_tag_unit #(
    parameter AXIS_WIDTH    = 128,
    parameter MATRIX_HEIGHT = 32,
    parameter MATRIX_WIDTH  = 32,
    parameter DATA_WIDTH    = 8,
    parameter TAG_WIDTH     = 8
)(
    input  logic                                clk,
    input  logic                                rst_n,

    input  logic                                tag_source_in,  // 0 = ROW, 1 = COL
    input  logic                                clr_en,         // clears position counter

    // AXI-Stream input
    input  logic [AXIS_WIDTH-1:0]               s_axis_tdata,
    input  logic                                s_axis_tvalid,
    output logic                                s_axis_tready,

    // Tagged output (dont_touch prevents cross-boundary retiming)
    (* dont_touch = "true" *) output logic [AXIS_WIDTH-1:0]  m_data,
    (* dont_touch = "true" *) output logic [AXIS_WIDTH-1:0]  m_tag,
    (* dont_touch = "true" *) output logic                   m_valid,
    (* dont_touch = "true" *) output logic                   m_valid_a,
    (* dont_touch = "true" *) output logic                   m_valid_b,
    input  logic                                             m_ready
);

    localparam NUM_LANES = AXIS_WIDTH / DATA_WIDTH;
    localparam DIM_WIDTH = TAG_WIDTH;
    // Offset counter needs enough bits for max matrix size (height * width)
    localparam OFFSET_WIDTH = DIM_WIDTH * 2;

    // Configurable dimension registers
    (* dont_touch = "true" *)
    logic [DIM_WIDTH-1:0] matrix_height_reg;

    (* dont_touch = "true" *)
    logic [DIM_WIDTH-1:0] matrix_width_reg;

    initial matrix_height_reg = MATRIX_HEIGHT[DIM_WIDTH-1:0];
    initial matrix_width_reg  = MATRIX_WIDTH[DIM_WIDTH-1:0];

    always_ff @(posedge clk) begin
        matrix_height_reg <= matrix_height_reg;
        matrix_width_reg  <= matrix_width_reg;
    end

    // Pre-registered width multiples (static values, breaks path from matrix_width_reg)
    (* dont_touch = "true" *) logic [DIM_WIDTH+1:0] width_x1;
    (* dont_touch = "true" *) logic [DIM_WIDTH+1:0] width_x2;
    (* dont_touch = "true" *) logic [DIM_WIDTH+1:0] width_x3;

    // Pre-registered addends for base counter: NUM_LANES - k*width
    // These are constants since width never changes. Using these, the base
    // counter feedback is just: base_col + pre_computed_constant (single adder).
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] stride_0;  // +NUM_LANES
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] stride_1;  // +NUM_LANES - width
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] stride_2;  // +NUM_LANES - 2*width
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] stride_3;  // +NUM_LANES - 3*width
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] stride_4;  // +NUM_LANES - 4*width

    always_ff @(posedge clk) begin
        width_x1 <= {2'b0, matrix_width_reg};
        width_x2 <= {1'b0, matrix_width_reg, 1'b0};
        width_x3 <= {2'b0, matrix_width_reg} + {1'b0, matrix_width_reg, 1'b0};

        stride_0 <= NUM_LANES;
        stride_1 <= NUM_LANES - {2'b0, matrix_width_reg};
        stride_2 <= NUM_LANES - {1'b0, matrix_width_reg, 1'b0};
        stride_3 <= NUM_LANES - ({2'b0, matrix_width_reg} + {1'b0, matrix_width_reg, 1'b0});
        stride_4 <= NUM_LANES - {matrix_width_reg, 2'b0};  // NUM_LANES - 4*width
    end

    // =========================================================================
    // Byte offset counter — simple +NUM_LANES adder in feedback loop
    // =========================================================================
    logic [OFFSET_WIDTH-1:0] byte_offset;

    // =========================================================================
    // Derive base_col and base_row from byte_offset using parallel subtraction
    // base_col = byte_offset % width, base_row = byte_offset / width
    // But we avoid division by tracking col/row in registered pre-computation.
    // Actually: we derive per-lane col directly from (byte_offset + i) using
    // the parallel subtraction. The "base_col" concept is gone — each lane
    // computes its position from the global offset.
    // =========================================================================

    logic [DIM_WIDTH-1:0] lane_row [NUM_LANES-1:0];
    logic [DIM_WIDTH-1:0] lane_col [NUM_LANES-1:0];
    logic [TAG_WIDTH-1:0] lane_tag [NUM_LANES-1:0];

    // Per-lane: compute (byte_offset + i) % width and (byte_offset + i) / width
    // using parallel subtraction from a pre-registered col/row base.

    // We maintain registered base_col and base_row derived from byte_offset,
    // updated with simple logic each cycle.
    logic [DIM_WIDTH-1:0] base_col;
    logic [DIM_WIDTH-1:0] base_row;

    // Pre-registered per-lane offsets: i - k*width (constants since width is static)
    // Merges the add-i and subtract-width into a single pre-computed constant.
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] lane_offset0 [NUM_LANES-1:0];
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] lane_offset1 [NUM_LANES-1:0];
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] lane_offset2 [NUM_LANES-1:0];
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] lane_offset3 [NUM_LANES-1:0];
    (* dont_touch = "true" *) logic signed [DIM_WIDTH+1:0] lane_offset4 [NUM_LANES-1:0];

    always_ff @(posedge clk) begin
        for (int i = 0; i < NUM_LANES; i++) begin
            lane_offset0[i] <= (DIM_WIDTH+2)'(i);
            lane_offset1[i] <= (DIM_WIDTH+2)'(i) - width_x1;
            lane_offset2[i] <= (DIM_WIDTH+2)'(i) - width_x2;
            lane_offset3[i] <= (DIM_WIDTH+2)'(i) - width_x3;
            lane_offset4[i] <= (DIM_WIDTH+2)'(i) - {matrix_width_reg, 2'b0};
        end
    end

    logic signed [DIM_WIDTH+1:0] lane_opt0 [NUM_LANES-1:0];
    logic signed [DIM_WIDTH+1:0] lane_opt1 [NUM_LANES-1:0];
    logic signed [DIM_WIDTH+1:0] lane_opt2 [NUM_LANES-1:0];
    logic signed [DIM_WIDTH+1:0] lane_opt3 [NUM_LANES-1:0];
    logic signed [DIM_WIDTH+1:0] lane_opt4 [NUM_LANES-1:0];
    logic [4:0]           lane_sel [NUM_LANES-1:0];

    always_comb begin
        for (int i = 0; i < NUM_LANES; i++) begin
            // Single adder per option: base_col + pre-registered offset
            lane_opt0[i] = {1'b0, 1'b0, base_col} + lane_offset0[i];
            lane_opt1[i] = {1'b0, 1'b0, base_col} + lane_offset1[i];
            lane_opt2[i] = {1'b0, 1'b0, base_col} + lane_offset2[i];
            lane_opt3[i] = {1'b0, 1'b0, base_col} + lane_offset3[i];
            lane_opt4[i] = {1'b0, 1'b0, base_col} + lane_offset4[i];

            // Parallel priority encoder per lane
            lane_sel[i][4] = ~lane_opt4[i][DIM_WIDTH+1];
            lane_sel[i][3] =  lane_opt4[i][DIM_WIDTH+1] & ~lane_opt3[i][DIM_WIDTH+1];
            lane_sel[i][2] =  lane_opt4[i][DIM_WIDTH+1] &  lane_opt3[i][DIM_WIDTH+1] & ~lane_opt2[i][DIM_WIDTH+1];
            lane_sel[i][1] =  lane_opt4[i][DIM_WIDTH+1] &  lane_opt3[i][DIM_WIDTH+1] &  lane_opt2[i][DIM_WIDTH+1] & ~lane_opt1[i][DIM_WIDTH+1];
            lane_sel[i][0] =  lane_opt4[i][DIM_WIDTH+1] &  lane_opt3[i][DIM_WIDTH+1] &  lane_opt2[i][DIM_WIDTH+1] &  lane_opt1[i][DIM_WIDTH+1];

            // OR-mux for lane_col
            for (int b = 0; b < DIM_WIDTH; b++) begin
                lane_col[i][b] = (lane_sel[i][4] & lane_opt4[i][b]) | (lane_sel[i][3] & lane_opt3[i][b]) |
                                 (lane_sel[i][2] & lane_opt2[i][b]) | (lane_sel[i][1] & lane_opt1[i][b]) |
                                 (lane_sel[i][0] & lane_opt0[i][b]);
            end

            // OR-mux for lane_row from pre-registered base_row + k
            for (int b = 0; b < DIM_WIDTH; b++) begin
                lane_row[i][b] = (lane_sel[i][4] & base_row_p4[b]) | (lane_sel[i][3] & base_row_p3[b]) |
                                 (lane_sel[i][2] & base_row_p2[b]) | (lane_sel[i][1] & base_row_p1[b]) |
                                 (lane_sel[i][0] & base_row[b]);
            end

            lane_tag[i] = tag_source_in ? lane_col[i] : lane_row[i];
        end
    end

    // =========================================================================
    // Handshake signals
    // =========================================================================
    logic out_ready;
    assign out_ready = !m_valid || m_ready;

    logic s1_valid;
    logic s1_ready;
    assign s1_ready = !s1_valid || out_ready;
    assign s_axis_tready = s1_ready;

    logic s1_handshake;
    assign s1_handshake = s_axis_tvalid && s1_ready;

    // =========================================================================
    // Stage 1: register tags and data
    // =========================================================================
    (* dont_touch = "true" *) logic [AXIS_WIDTH-1:0] s1_data;
    (* dont_touch = "true" *) logic [AXIS_WIDTH-1:0] s1_tag;
    logic s1_valid_a;
    logic s1_valid_b;

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            s1_valid   <= 1'b0;
            s1_valid_a <= 1'b0;
            s1_valid_b <= 1'b0;
        end else if (s1_ready) begin
            s1_valid   <= s_axis_tvalid;
            s1_valid_a <= s_axis_tvalid & ~tag_source_in;
            s1_valid_b <= s_axis_tvalid & tag_source_in;
            s1_data    <= s_axis_tdata;
            for (int i = 0; i < NUM_LANES; i++) begin
                s1_tag[i*TAG_WIDTH +: TAG_WIDTH] <= lane_tag[i];
            end
        end
    end

    // =========================================================================
    // Base counter: byte_offset increments by NUM_LANES.
    // base_col and base_row derived with a simple registered accumulator.
    // Feedback loop is just: base_col + 16, compare, subtract — same depth
    // as before but base_col does NOT go through the lane computation.
    // =========================================================================

    // Base counter feedback: base_col + stride (single adder per option, parallel)
    // stride_k = NUM_LANES - k*width (pre-registered constants)
    // MSB-only priority: if opt_k >= 0 and opt_{k+1} < 0, then opt_k is valid.
    logic signed [DIM_WIDTH+1:0] opt0, opt1, opt2, opt3, opt4;
    logic [2:0]           next_col_wraps;
    logic [DIM_WIDTH-1:0] next_base_col;

    assign opt0 = {1'b0, 1'b0, base_col} + stride_0;
    assign opt1 = {1'b0, 1'b0, base_col} + stride_1;
    assign opt2 = {1'b0, 1'b0, base_col} + stride_2;
    assign opt3 = {1'b0, 1'b0, base_col} + stride_3;
    assign opt4 = {1'b0, 1'b0, base_col} + stride_4;

    // Parallel priority encoder for base counter
    logic [4:0] opt_sel;  // one-hot select
    assign opt_sel[4] = ~opt4[DIM_WIDTH+1];
    assign opt_sel[3] =  opt4[DIM_WIDTH+1] & ~opt3[DIM_WIDTH+1];
    assign opt_sel[2] =  opt4[DIM_WIDTH+1] &  opt3[DIM_WIDTH+1] & ~opt2[DIM_WIDTH+1];
    assign opt_sel[1] =  opt4[DIM_WIDTH+1] &  opt3[DIM_WIDTH+1] &  opt2[DIM_WIDTH+1] & ~opt1[DIM_WIDTH+1];
    assign opt_sel[0] =  opt4[DIM_WIDTH+1] &  opt3[DIM_WIDTH+1] &  opt2[DIM_WIDTH+1] &  opt1[DIM_WIDTH+1];

    // OR-mux: each output bit is OR of (sel & data) across all options
    always_comb begin
        for (int b = 0; b < DIM_WIDTH; b++) begin
            next_base_col[b] = (opt_sel[4] & opt4[b]) | (opt_sel[3] & opt3[b]) |
                               (opt_sel[2] & opt2[b]) | (opt_sel[1] & opt1[b]) |
                               (opt_sel[0] & opt0[b]);
        end
        next_col_wraps = (opt_sel[4] ? 3'd4 : 3'd0) | (opt_sel[3] ? 3'd3 : 3'd0) |
                         (opt_sel[2] ? 3'd2 : 3'd0) | (opt_sel[1] ? 3'd1 : 3'd0);
    end

    // Pre-registered base_row + k values (removes adder from lane tag critical path)
    logic [DIM_WIDTH-1:0] base_row_p1;
    logic [DIM_WIDTH-1:0] base_row_p2;
    logic [DIM_WIDTH-1:0] base_row_p3;
    logic [DIM_WIDTH-1:0] base_row_p4;

    always_ff @(posedge clk) begin
        if (!rst_n || clr_en) begin
            base_col    <= '0;
            base_row    <= '0;
            base_row_p1 <= 8'd1;
            base_row_p2 <= 8'd2;
            base_row_p3 <= 8'd3;
            base_row_p4 <= 8'd4;
        end else if (s1_handshake) begin
            base_col    <= next_base_col;
            base_row    <= base_row    + {5'b0, next_col_wraps};
            base_row_p1 <= base_row_p1 + {5'b0, next_col_wraps};
            base_row_p2 <= base_row_p2 + {5'b0, next_col_wraps};
            base_row_p3 <= base_row_p3 + {5'b0, next_col_wraps};
            base_row_p4 <= base_row_p4 + {5'b0, next_col_wraps};
        end
    end

    // =========================================================================
    // Stage 2: output register
    // =========================================================================
    always_ff @(posedge clk) begin
        if (!rst_n) begin
            m_valid   <= 1'b0;
            m_valid_a <= 1'b0;
            m_valid_b <= 1'b0;
        end else if (out_ready) begin
            m_data    <= s1_data;
            m_tag     <= s1_tag;
            m_valid   <= s1_valid;
            m_valid_a <= s1_valid_a;
            m_valid_b <= s1_valid_b;
        end
    end

endmodule
