`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 02/23/2026 02:53:37 PM
// Design Name: 
// Module Name: sa_fsm
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module sa_fsm #(
    parameter SA_WIDTH = 32,
    parameter SA_HEIGHT = 32,
    parameter PTR_SIZE = 6,
    parameter WEST_INPUT_SIZE = 32,
    parameter NORTH_INPUT_SIZE = 32,
    parameter REG_WIDTH = 32,
    parameter ACCUM_SHIFT_PIPELINE_LATENCY = 1,
    parameter OUTPUT_WR_PIPELINE_LATENCY = 1,
    parameter PE_PIPELINE_LATENCY = 4  // Cycles from data entering PE to result in adder_out
)(
    input logic reset,
    input logic clk,
    
    input logic start,
    
    output logic a_rd_en,
    output logic b_rd_en,
    output logic output_wr_en,
    
    output logic done,
    output logic sa_accum_shift
);

    localparam FSM_REG_WIDTH = 4;
    logic [FSM_REG_WIDTH-1:0] state;
    logic [FSM_REG_WIDTH-1:0] next_state;
    
    localparam IDLE = 0;
    localparam SHIFT_OUT = 1;
    localparam SHIFT_OUT_WAIT = 2;
    localparam RUNNING = 3;
    localparam RUNNING_WAIT = 4;  // Wait for skewed data to finish flowing
    localparam DRAIN = 5;
    localparam DRAIN_WAIT = 6;
    localparam DONE = 7;

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
    
    logic [REG_WIDTH-1:0] counter;
    // shift_counter needs to count up to SA_HEIGHT-2 + (max(SA_HEIGHT,SA_WIDTH)-1) + PE_PIPELINE_LATENCY
    // With 1-cycle PE pass-through, propagation latency = max dimension - 1
    localparam MAX_DIM = (SA_HEIGHT > SA_WIDTH ? SA_HEIGHT : SA_WIDTH);
    localparam PE_PROPAGATION_LATENCY = MAX_DIM - 1;
    localparam MAX_SHIFT_COUNT = SA_HEIGHT + PE_PROPAGATION_LATENCY + PE_PIPELINE_LATENCY;
    logic [$clog2(MAX_SHIFT_COUNT+1)-1:0] shift_counter;
    logic [$clog2(MAX_SHIFT_COUNT+1)-1:0] shift_counter_next;
    logic [$clog2(SA_HEIGHT+1)-1:0] a_counter;
    logic [$clog2(SA_HEIGHT+1)-1:0] a_counter_next;
    logic [$clog2(SA_WIDTH+1)-1:0] b_counter;
    logic [$clog2(SA_WIDTH+1)-1:0] b_counter_next;
    logic a_done;
    logic b_done;
    logic [$clog2(ACCUM_SHIFT_PIPELINE_LATENCY+1)-1:0] accum_shift_wait_counter;
    logic [$clog2(ACCUM_SHIFT_PIPELINE_LATENCY+1)-1:0] accum_shift_wait_counter_next;
    logic [$clog2(OUTPUT_WR_PIPELINE_LATENCY+1)-1:0] output_wr_wait_counter;
    logic [$clog2(OUTPUT_WR_PIPELINE_LATENCY+1)-1:0] output_wr_wait_counter_next;
    
    always_comb begin
        next_state = state;
        
        case (state)
            IDLE: begin
                if (start) begin
                    next_state = SHIFT_OUT;
                end
            end

            SHIFT_OUT: begin
                if (shift_counter == SA_HEIGHT - 1) begin
                    next_state = SHIFT_OUT_WAIT;
                end
            end

            SHIFT_OUT_WAIT: begin
                if (accum_shift_wait_counter == ACCUM_SHIFT_PIPELINE_LATENCY - 1) begin
                    next_state = RUNNING;
                end
            end

            RUNNING: begin
                if (a_done && b_done) begin
                    next_state = RUNNING_WAIT;
                end
            end

            RUNNING_WAIT: begin
                // Wait for skewed data to finish flowing through array + PE pipeline latency
                // Need SA_HEIGHT-1 cycles for skew + PE_PROPAGATION_LATENCY + PE_PIPELINE_LATENCY
                if (shift_counter == SA_HEIGHT - 2 + PE_PROPAGATION_LATENCY + PE_PIPELINE_LATENCY) begin
                    next_state = DRAIN;
                end
            end

            DRAIN: begin
                // Need SA_HEIGHT cycles of output_wr_en, but it's delayed by ACCUM_SHIFT_PIPELINE_LATENCY + 1
                // So total DRAIN cycles = SA_HEIGHT + ACCUM_SHIFT_PIPELINE_LATENCY
                if (shift_counter == SA_HEIGHT + ACCUM_SHIFT_PIPELINE_LATENCY) begin
                    next_state = DRAIN_WAIT;
                end
            end

            DRAIN_WAIT: begin
                if (accum_shift_wait_counter == ACCUM_SHIFT_PIPELINE_LATENCY - 1 &&
                    output_wr_wait_counter == OUTPUT_WR_PIPELINE_LATENCY - 1) begin
                    next_state = DONE;
                end
            end

            DONE: begin
                next_state = IDLE;
            end

            default:
                next_state = IDLE;
        endcase
    end

    always_comb begin
        sa_accum_shift = 1'b0;
        a_rd_en = 1'b0;
        b_rd_en = 1'b0;
        output_wr_en = 1'b0;
        done = 1'b0;
        shift_counter_next = shift_counter;
        a_counter_next = a_counter;
        b_counter_next = b_counter;
        a_done = (a_counter == SA_HEIGHT);
        b_done = (b_counter == SA_WIDTH);

        accum_shift_wait_counter_next = accum_shift_wait_counter;
        output_wr_wait_counter_next = output_wr_wait_counter;

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
            // Reset wait counters at start of RUNNING (they were used in SHIFT_OUT_WAIT)
            if (a_counter == 0 && b_counter == 0) begin
                accum_shift_wait_counter_next = '0;
                output_wr_wait_counter_next = '0;
            end
            if (~a_done) begin
                a_rd_en = 1'b1;
                a_counter_next = a_counter + 1;
            end
            if (~b_done) begin
                b_rd_en = 1'b1;
                b_counter_next = b_counter + 1;
            end
            if (a_counter >= SA_HEIGHT - ACCUM_SHIFT_PIPELINE_LATENCY &&
                b_counter >= SA_WIDTH - ACCUM_SHIFT_PIPELINE_LATENCY) begin
                if (accum_shift_wait_counter != ACCUM_SHIFT_PIPELINE_LATENCY - 1) begin
                    accum_shift_wait_counter_next = accum_shift_wait_counter + 1;
                end
            end
            if (a_counter >= SA_HEIGHT - OUTPUT_WR_PIPELINE_LATENCY &&
                b_counter >= SA_WIDTH - OUTPUT_WR_PIPELINE_LATENCY) begin
                if (output_wr_wait_counter != OUTPUT_WR_PIPELINE_LATENCY - 1) begin
                    output_wr_wait_counter_next = output_wr_wait_counter + 1;
                end
            end
        end

        if (state == RUNNING_WAIT) begin
            // Wait for skewed data to finish flowing through the array + PE pipeline
            // No rd_en asserted, just count cycles
            // Reset shift_counter and wait counters on last cycle so DRAIN starts fresh
            if (shift_counter == SA_HEIGHT - 2 + PE_PROPAGATION_LATENCY + PE_PIPELINE_LATENCY) begin
                shift_counter_next = '0;
                accum_shift_wait_counter_next = '0;
                output_wr_wait_counter_next = '0;
            end else begin
                shift_counter_next = shift_counter + 1;
            end
        end

        if (state == DRAIN) begin
            sa_accum_shift = 1'b1;
            // Delay output_wr_en by ACCUM_SHIFT_PIPELINE_LATENCY + 1 cycles
            // to account for PE pipeline delay from accum_shift to accum_out
            // Plus one additional cycle for L2 drain module timing
            if (shift_counter >= ACCUM_SHIFT_PIPELINE_LATENCY + 1) begin
                output_wr_en = 1'b1;
            end
            shift_counter_next = shift_counter + 1;
        end

        if (state == DRAIN_WAIT) begin
            if (accum_shift_wait_counter != ACCUM_SHIFT_PIPELINE_LATENCY - 1) begin
                accum_shift_wait_counter_next = accum_shift_wait_counter + 1;
            end
            if (output_wr_wait_counter != OUTPUT_WR_PIPELINE_LATENCY - 1) begin
                output_wr_wait_counter_next = output_wr_wait_counter + 1;
            end
        end

        if (state == DONE) begin
            done = 1'b1;
        end
    end

endmodule
