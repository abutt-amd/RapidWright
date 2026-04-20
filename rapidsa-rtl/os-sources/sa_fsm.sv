`timescale 1ns / 1ps

module sa_fsm #(
    parameter SA_WIDTH = 32,
    parameter SA_HEIGHT = 32,
    parameter K_DIM = 32,
    parameter SIZE_REG_WIDTH = 16,
    parameter LATENCY_REG_WIDTH = 4,
    parameter DCU_CHAIN_LATENCY = 30,
    parameter ACCUM_SHIFT_PIPELINE_LATENCY = 0,
    parameter OUTPUT_WR_PIPELINE_LATENCY = 0,
    parameter PE_PIPELINE_LATENCY = 4
)(
    input  logic clk,
    input  logic reset,
    input  logic start,

    // MM2S sequencer control
    output logic mm2s_start,
    input  logic mm2s_done,

    // SA compute control
    output logic a_rd_en,
    output logic b_rd_en,
    output logic output_wr_en,
    output logic sa_accum_shift,

    // S2MM control
    output logic s2mm_start,
    input  logic s2mm_done,

    // Overall completion
    output logic done,
    output logic busy
);

    // State encoding
    localparam IDLE              = 0;
    localparam LOAD              = 1;
    localparam PROPAGATE_WAIT    = 2;
    localparam SHIFT_OUT         = 3;
    localparam SHIFT_OUT_WAIT    = 4;
    localparam RUNNING           = 5;
    localparam RUNNING_WAIT      = 6;
    localparam DRAIN             = 7;
    localparam DRAIN_WAIT        = 8;
    localparam S2MM_START_ST     = 9;
    localparam S2MM_WAIT         = 10;
    localparam DONE              = 11;

    // Configurable dimension registers
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] sa_width = SA_WIDTH;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] sa_height = SA_HEIGHT;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] k_dim = K_DIM;

    // Configurable latency registers
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] dcu_chain_latency = DCU_CHAIN_LATENCY;
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] accum_shift_pipeline_latency = ACCUM_SHIFT_PIPELINE_LATENCY;
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] output_wr_pipeline_latency = OUTPUT_WR_PIPELINE_LATENCY;
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] pe_pipeline_latency = PE_PIPELINE_LATENCY;

    // Pre-computed comparison targets (derived from configurable registers)
    logic [SIZE_REG_WIDTH-1:0] shift_out_target;
    logic [SIZE_REG_WIDTH-1:0] running_wait_target;
    logic [SIZE_REG_WIDTH-1:0] drain_exit_target;
    logic [SIZE_REG_WIDTH-1:0] output_wr_start_target;

    always_ff @(posedge clk) begin
        sa_width <= sa_width;
        sa_height <= sa_height;
        k_dim <= k_dim;
        dcu_chain_latency <= dcu_chain_latency;
        accum_shift_pipeline_latency <= accum_shift_pipeline_latency;
        output_wr_pipeline_latency <= output_wr_pipeline_latency;
        pe_pipeline_latency <= pe_pipeline_latency;
        shift_out_target <= sa_height - 1;
        running_wait_target <= sa_height + sa_width + pe_pipeline_latency - 3;
        drain_exit_target <= sa_height + accum_shift_pipeline_latency + 1;
        output_wr_start_target <= accum_shift_pipeline_latency + 1;
    end

    // State registers
    logic [3:0] state;
    logic [3:0] next_state;

    // Counters
    logic [SIZE_REG_WIDTH-1:0] shift_counter;
    logic [SIZE_REG_WIDTH-1:0] shift_counter_next;
    logic [SIZE_REG_WIDTH-1:0] run_remaining;
    logic [SIZE_REG_WIDTH-1:0] run_remaining_next;
    logic [SIZE_REG_WIDTH-1:0] propagate_counter;
    logic [SIZE_REG_WIDTH-1:0] propagate_counter_next;
    logic [LATENCY_REG_WIDTH-1:0] accum_shift_wait_counter;
    logic [LATENCY_REG_WIDTH-1:0] accum_shift_wait_counter_next;
    logic [LATENCY_REG_WIDTH-1:0] output_wr_wait_counter;
    logic [LATENCY_REG_WIDTH-1:0] output_wr_wait_counter_next;
    logic run_done;

    always_ff @(posedge clk) begin
        if (reset) begin
            state <= IDLE;
            shift_counter <= '0;
            run_remaining <= '0;
            propagate_counter <= '0;
            accum_shift_wait_counter <= '0;
            output_wr_wait_counter <= '0;
        end else begin
            state <= next_state;
            shift_counter <= shift_counter_next;
            run_remaining <= run_remaining_next;
            propagate_counter <= propagate_counter_next;
            accum_shift_wait_counter <= accum_shift_wait_counter_next;
            output_wr_wait_counter <= output_wr_wait_counter_next;
        end
    end

    // Next state logic
    always_comb begin
        next_state = state;

        case (state)
            IDLE: begin
                if (start) begin
                    next_state = LOAD;
                end
            end

            LOAD: begin
                if (mm2s_done) begin
                    next_state = PROPAGATE_WAIT;
                end
            end

            PROPAGATE_WAIT: begin
                if (propagate_counter == dcu_chain_latency) begin
                    next_state = SHIFT_OUT;
                end
            end

            SHIFT_OUT: begin
                if (shift_counter == shift_out_target) begin
                    next_state = SHIFT_OUT_WAIT;
                end
            end

            SHIFT_OUT_WAIT: begin
                if (accum_shift_wait_counter == accum_shift_pipeline_latency) begin
                    next_state = RUNNING;
                end
            end

            RUNNING: begin
                if (run_done) begin
                    next_state = RUNNING_WAIT;
                end
            end

            RUNNING_WAIT: begin
                if (shift_counter == running_wait_target) begin
                    next_state = DRAIN;
                end
            end

            DRAIN: begin
                if (shift_counter == drain_exit_target) begin
                    next_state = DRAIN_WAIT;
                end
            end

            DRAIN_WAIT: begin
                if (accum_shift_wait_counter == accum_shift_pipeline_latency &&
                    output_wr_wait_counter == output_wr_pipeline_latency) begin
                    next_state = S2MM_START_ST;
                end
            end

            S2MM_START_ST: begin
                next_state = S2MM_WAIT;
            end

            S2MM_WAIT: begin
                if (s2mm_done) begin
                    next_state = DONE;
                end
            end

            DONE: begin
                next_state = IDLE;
            end

            default: begin
                next_state = IDLE;
            end
        endcase
    end

    // Output and counter logic
    always_comb begin
        sa_accum_shift = 1'b0;
        a_rd_en = 1'b0;
        b_rd_en = 1'b0;
        output_wr_en = 1'b0;
        done = 1'b0;
        mm2s_start = 1'b0;
        s2mm_start = 1'b0;

        shift_counter_next = shift_counter;
        run_remaining_next = run_remaining;
        propagate_counter_next = propagate_counter;
        accum_shift_wait_counter_next = accum_shift_wait_counter;
        output_wr_wait_counter_next = output_wr_wait_counter;

        run_done = (run_remaining == '0);

        if (state == IDLE) begin
            shift_counter_next = '0;
            run_remaining_next = k_dim;
            propagate_counter_next = '0;
            accum_shift_wait_counter_next = '0;
            output_wr_wait_counter_next = '0;
            if (start) begin
                mm2s_start = 1'b1;
            end
        end

        if (state == LOAD) begin
            // Waiting for mm2s_done
        end

        if (state == PROPAGATE_WAIT) begin
            propagate_counter_next = propagate_counter + 1;
        end

        if (state == SHIFT_OUT) begin
            sa_accum_shift = 1'b1;
            shift_counter_next = shift_counter + 1;
        end

        if (state == SHIFT_OUT_WAIT) begin
            accum_shift_wait_counter_next = accum_shift_wait_counter + 1;
        end

        if (state == RUNNING) begin
            shift_counter_next = '0;

            if (!run_done) begin
                a_rd_en = 1'b1;
                b_rd_en = 1'b1;
                run_remaining_next = run_remaining - 1'b1;
            end
        end

        if (state == RUNNING_WAIT) begin
            if (shift_counter == running_wait_target) begin
                shift_counter_next = '0;
                accum_shift_wait_counter_next = '0;
                output_wr_wait_counter_next = '0;
            end else begin
                shift_counter_next = shift_counter + 1;
            end
        end

        if (state == DRAIN) begin
            sa_accum_shift = 1'b1;
            if (shift_counter > output_wr_start_target) begin
                output_wr_en = 1'b1;
            end
            shift_counter_next = shift_counter + 1;
        end

        if (state == DRAIN_WAIT) begin
            if (accum_shift_wait_counter != accum_shift_pipeline_latency) begin
                accum_shift_wait_counter_next = accum_shift_wait_counter + 1;
            end
            if (output_wr_wait_counter != output_wr_pipeline_latency) begin
                output_wr_wait_counter_next = output_wr_wait_counter + 1;
            end
        end

        if (state == S2MM_START_ST) begin
            s2mm_start = 1'b1;
        end

        if (state == S2MM_WAIT) begin
            // Waiting for s2mm_done
        end

        if (state == DONE) begin
            done = 1'b1;
        end

        busy = (state != IDLE);
    end

endmodule
