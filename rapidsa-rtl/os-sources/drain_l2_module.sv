`timescale 1ns / 1ps

module drain_l2_module #(
    parameter DATA_WIDTH = 8,
    parameter COLUMN_ELEMENTS = 8,      // Number of elements in this column to drain
    parameter UPSTREAM_ELEMENTS = 0     // Number of elements to forward from upstream
)(
    input  logic clk,
    input  logic rst_n,

    // Column FIFO interface (from local column)
    input  logic [DATA_WIDTH-1:0] col_fifo_data,
    input  logic                  col_fifo_empty,
    output logic                  col_fifo_rd_en,

    // AXI-Stream downstream interface (toward memory/L3)
    output logic [DATA_WIDTH-1:0] m_axis_downstream_tdata,
    output logic                  m_axis_downstream_tvalid,
    input  logic                  m_axis_downstream_tready,

    // AXI-Stream upstream interface (from upstream L2, closer to PEs)
    input  logic [DATA_WIDTH-1:0] s_axis_upstream_tdata,
    input  logic                  s_axis_upstream_tvalid,
    output logic                  s_axis_upstream_tready
);

    // Counter width based on max of column and downstream elements
    localparam COUNT_WIDTH = $clog2(COLUMN_ELEMENTS > UPSTREAM_ELEMENTS ?
                                    COLUMN_ELEMENTS + 1 : UPSTREAM_ELEMENTS + 1);

    typedef enum logic [1:0] {
        DRAIN_LOCAL,        // Drain elements from local column FIFO
        FORWARD_UPSTREAM, // Forward elements from upstream L2
        IDLE                // Waiting for next drain cycle
    } state_t;

    state_t state, next_state;

    logic [COUNT_WIDTH-1:0] local_count, local_count_next;
    logic [COUNT_WIDTH-1:0] upstream_count, upstream_count_next;

    // FIFO has 1-cycle read latency, so we need to track when data is valid
    // rd_en asserted on cycle N -> data valid on cycle N+1
    logic fifo_rd_pending;     // rd_en was asserted last cycle, data arriving now
    logic fifo_data_valid;     // We have valid data in fifo_data_reg
    logic [DATA_WIDTH-1:0] fifo_data_reg;

    // State machine
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state <= DRAIN_LOCAL;
            local_count <= '0;
            upstream_count <= '0;
            fifo_rd_pending <= 1'b0;
            fifo_data_valid <= 1'b0;
            fifo_data_reg <= '0;
        end else begin
            state <= next_state;
            local_count <= local_count_next;
            upstream_count <= upstream_count_next;

            // Track FIFO read latency: rd_en on cycle N -> data on cycle N+1
            fifo_rd_pending <= col_fifo_rd_en;

            if (fifo_rd_pending) begin
                // Data is now valid from the read issued last cycle
                fifo_data_valid <= 1'b1;
                fifo_data_reg <= col_fifo_data;
            end else if (fifo_data_valid && m_axis_downstream_tready) begin
                fifo_data_valid <= 1'b0;
            end
        end
    end

    // State transition logic
    always_comb begin
        // Defaults
        next_state = state;
        local_count_next = local_count;
        upstream_count_next = upstream_count;

        case (state)
            DRAIN_LOCAL: begin
                if (local_count < COLUMN_ELEMENTS) begin
                    // Still draining local FIFO
                    if (fifo_data_valid && m_axis_downstream_tready) begin
                        local_count_next = local_count + 1;
                    end
                end else begin
                    // Done with local, move to forwarding upstream elements
                    if (UPSTREAM_ELEMENTS > 0) begin
                        next_state = FORWARD_UPSTREAM;
                    end else begin
                        next_state = IDLE;
                        local_count_next = '0;
                    end
                end
            end

            FORWARD_UPSTREAM: begin
                if (upstream_count < UPSTREAM_ELEMENTS) begin
                    if (s_axis_upstream_tvalid && m_axis_downstream_tready) begin
                        upstream_count_next = upstream_count + 1;
                    end
                end else begin
                    // Done with upstream forwarding, go to idle
                    next_state = IDLE;
                    local_count_next = '0;
                    upstream_count_next = '0;
                end
            end

            IDLE: begin
                // Wait for local FIFO to have data again (next drain cycle)
                if (!col_fifo_empty) begin
                    next_state = DRAIN_LOCAL;
                end
            end

            default: begin
                next_state = IDLE;
            end
        endcase
    end

    // Output logic
    always_comb begin
        // Defaults
        col_fifo_rd_en = 1'b0;
        m_axis_downstream_tdata = '0;
        m_axis_downstream_tvalid = 1'b0;
        s_axis_upstream_tready = 1'b0;

        if (state == DRAIN_LOCAL && local_count < COLUMN_ELEMENTS) begin
            if (!col_fifo_empty && !fifo_data_valid && !fifo_rd_pending) begin
                // Read from FIFO (data available next cycle)
                // Only read if no read is in flight and no data waiting
                col_fifo_rd_en = 1'b1;
            end else if (fifo_data_valid) begin
                // Data is valid, send downstream
                m_axis_downstream_tdata = fifo_data_reg;
                m_axis_downstream_tvalid = 1'b1;

                if (m_axis_downstream_tready && !col_fifo_empty && !fifo_rd_pending) begin
                    // Read next if available and no read in flight
                    col_fifo_rd_en = 1'b1;
                end
            end
        end else if (state == FORWARD_UPSTREAM && upstream_count < UPSTREAM_ELEMENTS) begin
            // Pass through from upstream to downstream
            s_axis_upstream_tready = m_axis_downstream_tready;
            m_axis_downstream_tdata = s_axis_upstream_tdata;
            m_axis_downstream_tvalid = s_axis_upstream_tvalid;
        end
    end

endmodule
