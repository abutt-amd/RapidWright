`timescale 1ns / 1ps

module sa_fsm #(
    parameter SA_WIDTH = 32,
    parameter SA_HEIGHT = 32,
    parameter K_DIM = 32,
    parameter SIZE_REG_WIDTH = 32,
    parameter LATENCY_REG_WIDTH = 4,
    parameter ACCUM_SHIFT_PIPELINE_LATENCY = 0,
    parameter OUTPUT_WR_PIPELINE_LATENCY = 0,
    parameter PE_PIPELINE_LATENCY = 4
)(
    input  logic clk,
    input  logic reset,
    input  logic start,
    output logic a_rd_en,
    output logic b_rd_en,
    output logic output_wr_en,
    output logic done,
    output logic sa_accum_shift
);

    // State encoding
    localparam IDLE           = 0;
    localparam SHIFT_OUT      = 1;
    localparam SHIFT_OUT_WAIT = 2;
    localparam RUNNING        = 3;
    localparam RUNNING_WAIT   = 4;
    localparam DRAIN          = 5;
    localparam DRAIN_WAIT     = 6;
    localparam DONE           = 7;

    // Configurable dimension registers
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] sa_width = SA_WIDTH;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] sa_height = SA_HEIGHT;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] k_dim = K_DIM;

    // Configurable latency registers
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] accum_shift_pipeline_latency = ACCUM_SHIFT_PIPELINE_LATENCY;
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] output_wr_pipeline_latency = OUTPUT_WR_PIPELINE_LATENCY;
    (* dont_touch = "true" *) logic [LATENCY_REG_WIDTH-1:0] pe_pipeline_latency = PE_PIPELINE_LATENCY;

    // Pre-computed comparison targets
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] shift_out_target = SA_HEIGHT - 1;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] running_wait_target = SA_HEIGHT + SA_WIDTH + PE_PIPELINE_LATENCY - 3;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] drain_exit_target = SA_HEIGHT + ACCUM_SHIFT_PIPELINE_LATENCY + 1;
    (* dont_touch = "true" *) logic [SIZE_REG_WIDTH-1:0] output_wr_start_target = ACCUM_SHIFT_PIPELINE_LATENCY + 1;

    always_ff @(posedge clk) begin
        sa_width <= sa_width;
        sa_height <= sa_height;
        k_dim <= k_dim;
        accum_shift_pipeline_latency <= accum_shift_pipeline_latency;
        output_wr_pipeline_latency <= output_wr_pipeline_latency;
        pe_pipeline_latency <= pe_pipeline_latency;
        shift_out_target <= shift_out_target;
        running_wait_target <= running_wait_target;
        drain_exit_target <= drain_exit_target;
        output_wr_start_target <= output_wr_start_target;
    end

    // State registers
    logic [3:0] state;
    logic [3:0] next_state;

    // Counters
    logic [SIZE_REG_WIDTH-1:0] shift_counter;
    logic [SIZE_REG_WIDTH-1:0] shift_counter_next;
    logic [SIZE_REG_WIDTH-1:0] a_counter;
    logic [SIZE_REG_WIDTH-1:0] a_counter_next;
    logic [SIZE_REG_WIDTH-1:0] b_counter;
    logic [SIZE_REG_WIDTH-1:0] b_counter_next;
    logic [LATENCY_REG_WIDTH-1:0] accum_shift_wait_counter;
    logic [LATENCY_REG_WIDTH-1:0] accum_shift_wait_counter_next;
    logic [LATENCY_REG_WIDTH-1:0] output_wr_wait_counter;
    logic [LATENCY_REG_WIDTH-1:0] output_wr_wait_counter_next;
    logic a_done;
    logic b_done;

    always_ff @(posedge clk) begin
        if (reset) begin
            state <= IDLE;
            shift_counter <= '0;
            a_counter <= '0;
            b_counter <= '0;
            accum_shift_wait_counter <= '0;
            output_wr_wait_counter <= '0;
        end else begin
            state <= next_state;
            shift_counter <= shift_counter_next;
            a_counter <= a_counter_next;
            b_counter <= b_counter_next;
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
                if (a_done && b_done) begin
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

        shift_counter_next = shift_counter;
        a_counter_next = a_counter;
        b_counter_next = b_counter;
        accum_shift_wait_counter_next = accum_shift_wait_counter;
        output_wr_wait_counter_next = output_wr_wait_counter;

        a_done = (a_counter == k_dim);
        b_done = (b_counter == k_dim);

        if (state == IDLE) begin
            shift_counter_next = '0;
            a_counter_next = '0;
            b_counter_next = '0;
            accum_shift_wait_counter_next = '0;
            output_wr_wait_counter_next = '0;
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

            if (~a_done) begin
                a_rd_en = 1'b1;
                a_counter_next = a_counter + 1;
            end

            if (~b_done) begin
                b_rd_en = 1'b1;
                b_counter_next = b_counter + 1;
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

        if (state == DONE) begin
            done = 1'b1;
        end
    end

endmodule
