`timescale 1ns / 1ps

// Edge Buffer Tile — receives K-major broadcast data, captures per-unit bytes
// via static byte lane selection, stores in per-unit FIFOs.
//
// Each unit has a dont_touch byte_lane register that determines which byte
// of the 128-bit input word it captures. This is set once at build time
// via RegisterInitTools based on the tile's position in the array.
//
// For N > 16: each unit also has a word_index register. The tile only captures
// when the broadcast word counter matches its word_index. For N <= 16,
// word_index is 0 and the word counter input can be tied to 0.

module weight_edge_buffer_tile #(
    parameter AXIS_WIDTH    = 128,
    parameter DATA_WIDTH    = 8,
    parameter NUM_UNITS     = 4,
    parameter FIFO_PTR_SIZE = 6,
    parameter NUM_LANES     = AXIS_WIDTH / DATA_WIDTH   // 16
)(
    input  logic clk,
    input  logic reset,

    // Broadcast data input (from MM2S channel or previous tile in forwarding chain)
    input  logic [AXIS_WIDTH-1:0]  s_data,
    input  logic                   s_valid,

    // Forwarding output (to next tile)
    output logic [AXIS_WIDTH-1:0]  m_data,
    output logic                   m_valid,

    // Word index for N > 16 (broadcast sideband)
    input  logic [3:0]             s_word_index,
    output logic [3:0]             m_word_index,

    // FIFO read interface (during compute)
    input  logic                   rd_en,
    output logic                   rd_en_out,
    output logic [DATA_WIDTH-1:0]  dout       [NUM_UNITS-1:0],
    output logic                   dout_valid [NUM_UNITS-1:0]
);

    // =========================================================================
    // Per-unit configuration registers (set by RegisterInitTools at build time)
    // =========================================================================

    // Which byte lane (0-15) this unit captures from the 128-bit word
    (* dont_touch = "true" *) logic [3:0] byte_lane [NUM_UNITS-1:0];
    // Which word index this unit captures (0 for N <= 16)
    (* dont_touch = "true" *) logic [3:0] word_index [NUM_UNITS-1:0];

    // Initialize to default values (overridden by RegisterInitTools)
    genvar g;
    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_init
            initial byte_lane[g] = g;
            initial word_index[g] = 0;
            always_ff @(posedge clk) begin
                byte_lane[g] <= byte_lane[g];
                word_index[g] <= word_index[g];
            end
        end
    endgenerate

    // =========================================================================
    // Input boundary registers — terminate the long inter-module route from
    // MM2S (or the previous tile) at a clean FF before the FIFO write logic
    // and forwarding output. See edge_buffer_tile.sv for full description.
    // =========================================================================
    logic [AXIS_WIDTH-1:0] s_data_reg;
    logic                  s_valid_reg;
    logic [3:0]            s_word_index_reg;

    always_ff @(posedge clk) begin
        if (reset) begin
            s_valid_reg      <= 1'b0;
            s_word_index_reg <= '0;
        end else begin
            s_data_reg       <= s_data;
            s_valid_reg      <= s_valid;
            s_word_index_reg <= s_word_index;
        end
    end

    assign m_data       = s_data_reg;
    assign m_valid      = s_valid_reg;
    assign m_word_index = s_word_index_reg;

    // =========================================================================
    // Per-unit FIFO write: capture byte from broadcast word
    // =========================================================================
    logic [DATA_WIDTH-1:0] fifo_wdata [NUM_UNITS-1:0];
    logic                  fifo_wen   [NUM_UNITS-1:0];
    logic                  fifo_empty [NUM_UNITS-1:0];

    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_capture
            // Static byte extraction — the byte_lane register selects which 8-bit
            // slice of s_data_reg to capture. Since byte_lane is dont_touch and
            // feeds back to itself, synthesis treats this as a constant mux.
            always_comb begin
                fifo_wdata[g] = s_data_reg[byte_lane[g]*8 +: 8];
                fifo_wen[g]   = s_valid_reg && (s_word_index_reg == word_index[g]);
            end
        end
    endgenerate

    // =========================================================================
    // rd_en pipeline (registered between units, same as original DCU)
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
    // FIFO tile (same as original design)
    // =========================================================================
    logic [FIFO_PTR_SIZE:0] fifo_count [NUM_UNITS-1:0];
    logic                   fifo_full  [NUM_UNITS-1:0];

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
        .full(fifo_full)
    );

    generate
        for (g = 0; g < NUM_UNITS; g++) begin : gen_dout_valid
            always_ff @(posedge clk) begin
                if (reset)
                    dout_valid[g] <= 1'b0;
                else
                    dout_valid[g] <= ~fifo_empty[g] && rd_en_arr[g];
            end
        end
    endgenerate

endmodule
