`timescale 1ns / 1ps

// MM2S NoC Sequencer - Drives a single DMA controller for two back-to-back
// transfers (activation then weight) through the same NoC NMU path.

module mm2s_noc_sequencer (
    input  logic        clk,
    input  logic        rst_n,

    // External control interface
    input  logic        start,
    input  logic [31:0] src_addr_a,
    input  logic [22:0] transfer_length_a,
    input  logic [31:0] src_addr_b,
    input  logic [22:0] transfer_length_b,
    output logic        done,
    output logic        busy,
    output logic        error,

    // To DMA controller
    output logic        dma_start,
    output logic [31:0] dma_src_addr,
    output logic [22:0] dma_transfer_length,
    input  logic        dma_done,
    input  logic        dma_busy,
    input  logic        dma_error,

    // To DTU / demux
    output logic        active_channel,   // 0 = activation/ROW, 1 = weight/COL
    output logic        clr_en
);

    typedef enum logic [2:0] {
        IDLE,
        START_A,
        WAIT_A,
        START_B,
        WAIT_B,
        DONE_ST,
        ERROR_ST
    } state_t;

    state_t state, next_state;

    // Registered copies of external inputs
    logic [31:0] addr_a_reg, addr_b_reg;
    logic [22:0] len_a_reg, len_b_reg;

    // Next-state logic
    always_comb begin
        next_state = state;
        case (state)
            IDLE:    if (start) next_state = START_A;
            START_A: next_state = WAIT_A;
            WAIT_A: begin
                if (dma_error) next_state = ERROR_ST;
                else if (dma_done) next_state = START_B;
            end
            START_B: next_state = WAIT_B;
            WAIT_B: begin
                if (dma_error) next_state = ERROR_ST;
                else if (dma_done) next_state = DONE_ST;
            end
            DONE_ST:  next_state = IDLE;
            ERROR_ST: next_state = IDLE;
            default:  next_state = IDLE;
        endcase
    end

    // State register
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            state <= IDLE;
        else
            state <= next_state;
    end

    // Datapath
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            addr_a_reg <= '0;
            addr_b_reg <= '0;
            len_a_reg  <= '0;
            len_b_reg  <= '0;
        end else if (state == IDLE && start) begin
            addr_a_reg <= src_addr_a;
            addr_b_reg <= src_addr_b;
            len_a_reg  <= transfer_length_a;
            len_b_reg  <= transfer_length_b;
        end
    end

    // Output logic
    assign dma_start = (state == START_A) || (state == START_B);
    assign dma_src_addr = (state == START_B || state == WAIT_B) ? addr_b_reg : addr_a_reg;
    assign dma_transfer_length = (state == START_B || state == WAIT_B) ? len_b_reg : len_a_reg;

    assign active_channel = (state == START_B) || (state == WAIT_B);
    assign clr_en = (state == START_A) || (state == START_B);

    assign done  = (state == DONE_ST);
    assign busy  = (state != IDLE);
    assign error = (state == ERROR_ST);

endmodule
