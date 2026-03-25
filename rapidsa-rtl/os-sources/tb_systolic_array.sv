`timescale 1ns / 1ps

module tb_systolic_array;

    // Parameters matching the 8x8 GEMM tile array (each tile is 4x4 PEs = 32x32 total)
    localparam DATA_WIDTH = 8;
    localparam TAG_WIDTH  = 8;
    localparam N_TILES    = 8;       // tiles per row/column
    localparam TILE_DIM   = 4;       // PEs per tile dimension
    localparam SA_DIM     = N_TILES * TILE_DIM; // 32 total PEs per dimension
    localparam K_DIM      = 4;       // inner dimension for matrix multiply (keep small for test)

    localparam CLK_PERIOD = 2.0;

    // Clock and control
    logic clk = 0;
    logic rst_n;
    logic start;
    logic done;
    logic output_wr_en;

    // B matrix AXI-stream (north DCU chain)
    logic [DATA_WIDTH-1:0] b_s_data;
    logic [TAG_WIDTH-1:0]  b_s_tag;
    logic                  b_s_valid;
    logic                  b_s_ready;
    logic [DATA_WIDTH-1:0] b_m_data;
    logic [TAG_WIDTH-1:0]  b_m_tag;
    logic                  b_m_valid;
    logic                  b_m_ready;

    // A matrix AXI-stream (west DCU chain)
    logic [DATA_WIDTH-1:0] a_s_data;
    logic [TAG_WIDTH-1:0]  a_s_tag;
    logic                  a_s_valid;
    logic                  a_s_ready;
    logic [DATA_WIDTH-1:0] a_m_data;
    logic [TAG_WIDTH-1:0]  a_m_tag;
    logic                  a_m_valid;
    logic                  a_m_ready;

    // Clock generation
    always #(CLK_PERIOD/2) clk = ~clk;

    // DUT instantiation
    // Port names must match the EDIF netlist exported by RapidSANetlistBuilder
    systolic_array dut (
        .clk(clk),
        .rst_n(rst_n),
        .start(start),
        .done(done),
        .output_wr_en(output_wr_en),

        .b_s_data(b_s_data),
        .b_s_tag(b_s_tag),
        .b_s_valid(b_s_valid),
        .b_s_ready(b_s_ready),
        .b_m_data(b_m_data),
        .b_m_tag(b_m_tag),
        .b_m_valid(b_m_valid),
        .b_m_ready(b_m_ready),

        .a_s_data(a_s_data),
        .a_s_tag(a_s_tag),
        .a_s_valid(a_s_valid),
        .a_s_ready(a_s_ready),
        .a_m_data(a_m_data),
        .a_m_tag(a_m_tag),
        .a_m_valid(a_m_valid),
        .a_m_ready(a_m_ready)
    );

    // Test matrices (small values for easy verification)
    // A is SA_DIM x K_DIM, B is K_DIM x SA_DIM
    logic signed [DATA_WIDTH-1:0] A_matrix [SA_DIM-1:0][K_DIM-1:0];
    logic signed [DATA_WIDTH-1:0] B_matrix [K_DIM-1:0][SA_DIM-1:0];

    // Expected result C = A * B (SA_DIM x SA_DIM)
    logic signed [2*DATA_WIDTH-1:0] C_expected [SA_DIM-1:0][SA_DIM-1:0];

    // Initialize test matrices and compute expected result
    task init_matrices();
        // A: identity-like pattern (A[i][k] = (i == k) ? 1 : 0) for first K_DIM rows,
        // rest are zero. Keeps results simple to verify.
        for (int i = 0; i < SA_DIM; i++) begin
            for (int k = 0; k < K_DIM; k++) begin
                A_matrix[i][k] = (i < K_DIM && i == k) ? 8'd1 : 8'd0;
            end
        end

        // B: simple incrementing pattern
        for (int k = 0; k < K_DIM; k++) begin
            for (int j = 0; j < SA_DIM; j++) begin
                B_matrix[k][j] = DATA_WIDTH'((k + j) & 8'hF);  // small values to avoid overflow
            end
        end

        // Compute expected C = A * B
        for (int i = 0; i < SA_DIM; i++) begin
            for (int j = 0; j < SA_DIM; j++) begin
                C_expected[i][j] = 0;
                for (int k = 0; k < K_DIM; k++) begin
                    C_expected[i][j] += A_matrix[i][k] * B_matrix[k][j];
                end
            end
        end
    endtask

    // AXI-stream sender task: sends data for all units in the DCU chain
    // For north DCUs: each DCU tile i has units with tag = i*TILE_DIM + unit_idx
    // For west DCUs: same scheme with rows
    // Data is sent K_DIM times per unit (one per k iteration)
    task automatic send_matrix_data(
        input logic signed [DATA_WIDTH-1:0] matrix_data [SA_DIM-1:0][K_DIM-1:0],
        input bit is_b_matrix  // 1 = B (north), 0 = A (west)
    );
        // Send K_DIM rounds of data. In each round, send one value per unit.
        // The daisy chain routes by tag, so send in tag order.
        for (int k = 0; k < K_DIM; k++) begin
            for (int unit = 0; unit < SA_DIM; unit++) begin
                if (is_b_matrix) begin
                    b_s_data  <= B_matrix[k][unit];
                    b_s_tag   <= TAG_WIDTH'(unit);
                    b_s_valid <= 1'b1;
                    @(posedge clk);
                    while (!b_s_ready) @(posedge clk);
                end else begin
                    a_s_data  <= A_matrix[unit][k];
                    a_s_tag   <= TAG_WIDTH'(unit);
                    a_s_valid <= 1'b1;
                    @(posedge clk);
                    while (!a_s_ready) @(posedge clk);
                end
            end
        end

        // Deassert valid
        if (is_b_matrix) begin
            b_s_valid <= 1'b0;
        end else begin
            a_s_valid <= 1'b0;
        end
    endtask

    // Main test sequence
    initial begin
        // Initialize signals
        rst_n     = 1'b0;
        start     = 1'b0;
        b_s_data  = '0;
        b_s_tag   = '0;
        b_s_valid = 1'b0;
        b_m_ready = 1'b1;  // always accept master output (drain unused data)
        a_s_data  = '0;
        a_s_tag   = '0;
        a_s_valid = 1'b0;
        a_m_ready = 1'b1;

        init_matrices();

        // Reset
        repeat (10) @(posedge clk);
        rst_n = 1'b1;
        repeat (5) @(posedge clk);

        $display("=== Loading matrices into FIFOs ===");

        // Load A and B matrices in parallel
        fork
            send_matrix_data(B_matrix, 1);  // won't compile directly, see note below
            send_matrix_data(A_matrix, 0);
        join

        $display("=== Matrix loading complete ===");
        repeat (10) @(posedge clk);

        // Start computation
        $display("=== Starting computation ===");
        start <= 1'b1;
        @(posedge clk);
        start <= 1'b0;

        // Wait for done
        @(posedge done);
        $display("=== Computation done! ===");

        repeat (100) @(posedge clk);

        $display("=== Simulation complete ===");
        $finish;
    end

    // Monitor output_wr_en
    always @(posedge clk) begin
        if (output_wr_en) begin
            $display("  [%0t] output_wr_en asserted", $time);
        end
    end

    // Timeout watchdog
    initial begin
        #(CLK_PERIOD * 100000);
        $display("ERROR: Simulation timed out!");
        $finish;
    end

    // Optional: dump waveforms
    initial begin
        $dumpfile("systolic_array.vcd");
        $dumpvars(0, tb_systolic_array);
    end

endmodule
