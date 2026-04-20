// SA FSM Wrapper - Pure Verilog wrapper for SystemVerilog module

module sa_fsm_wrapper #(
    parameter SA_WIDTH = 32,
    parameter SA_HEIGHT = 32,
    parameter K_DIM = 32,
    parameter SIZE_REG_WIDTH = 16,
    parameter LATENCY_REG_WIDTH = 4,
    parameter DCU_CHAIN_LATENCY = 30,
    parameter ACCUM_SHIFT_PIPELINE_LATENCY = 0,
    parameter OUTPUT_WR_PIPELINE_LATENCY = 0,
    parameter PE_PIPELINE_LATENCY = 4
) (
    input  wire clk,
    input  wire reset,
    input  wire start,

    // MM2S sequencer control
    output wire mm2s_start,
    input  wire mm2s_done,

    // SA compute control
    output wire a_rd_en,
    output wire b_rd_en,
    output wire output_wr_en,
    output wire sa_accum_shift,

    // S2MM control
    output wire s2mm_start,
    input  wire s2mm_done,

    // Overall completion
    output wire done,
    output wire busy
);

    sa_fsm #(
        .SA_WIDTH(SA_WIDTH),
        .SA_HEIGHT(SA_HEIGHT),
        .K_DIM(K_DIM),
        .SIZE_REG_WIDTH(SIZE_REG_WIDTH),
        .LATENCY_REG_WIDTH(LATENCY_REG_WIDTH),
        .DCU_CHAIN_LATENCY(DCU_CHAIN_LATENCY),
        .ACCUM_SHIFT_PIPELINE_LATENCY(ACCUM_SHIFT_PIPELINE_LATENCY),
        .OUTPUT_WR_PIPELINE_LATENCY(OUTPUT_WR_PIPELINE_LATENCY),
        .PE_PIPELINE_LATENCY(PE_PIPELINE_LATENCY)
    ) inst (
        .clk             (clk),
        .reset           (reset),
        .start           (start),

        .mm2s_start      (mm2s_start),
        .mm2s_done       (mm2s_done),

        .a_rd_en         (a_rd_en),
        .b_rd_en         (b_rd_en),
        .output_wr_en    (output_wr_en),
        .sa_accum_shift  (sa_accum_shift),

        .s2mm_start      (s2mm_start),
        .s2mm_done       (s2mm_done),

        .done            (done),
        .busy            (busy)
    );

endmodule
