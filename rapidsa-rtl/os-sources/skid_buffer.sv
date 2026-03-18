`timescale 1ns / 1ps

module skid_buffer #(
    parameter WIDTH = 8
)(
    input  logic             clk,
    input  logic             rst_n,

    input  logic [WIDTH-1:0] s_data,
    input  logic             s_valid,
    output logic             s_ready,

    output logic [WIDTH-1:0] m_data,
    output logic             m_valid,
    input  logic             m_ready
);

    logic [WIDTH-1:0] buf_data;
    logic             buf_valid;

    // s_ready is purely registered — breaks the ready path
    assign s_ready = !buf_valid;

    assign m_data  = buf_valid ? buf_data : s_data;
    assign m_valid = buf_valid || s_valid;

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            buf_valid <= 1'b0;
        end else if (s_valid && s_ready && !m_ready) begin
            // Downstream stalled: capture into skid register
            buf_data  <= s_data;
            buf_valid <= 1'b1;
        end else if (m_ready) begin
            buf_valid <= 1'b0;
        end
    end

endmodule
