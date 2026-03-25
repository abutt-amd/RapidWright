`timescale 1ns / 1ps

module dcu_fifo_tile_west #(
    parameter DATA_WIDTH = 8,
    parameter TAG_WIDTH = 8,
    parameter NUM_UNITS = 4,
    parameter FIFO_PTR_SIZE = 6,
    parameter ID_WIDTH = 8,
    parameter ID_OFFSET = 0
)(
    input logic clk,
    input logic reset,

    input logic [DATA_WIDTH-1:0] s_data,
    input logic [TAG_WIDTH-1:0]  s_tag,
    input logic                  s_valid,
    output logic                 s_ready,

    output logic [DATA_WIDTH-1:0] m_data,
    output logic [TAG_WIDTH-1:0]  m_tag,
    output logic                  m_valid,
    input logic                   m_ready,

    input logic                   rd_en,
    output logic                  rd_en_out,
    output logic [DATA_WIDTH-1:0] dout       [NUM_UNITS-1:0],
    output logic                  dout_valid [NUM_UNITS-1:0]
);

    // ID register initialized to ID_OFFSET, feeds back to itself
    (* dont_touch = "true" *) logic [ID_WIDTH-1:0] id_reg = ID_OFFSET;
    always_ff @(posedge clk) begin
        id_reg <= id_reg;
    end

    // Chain wires between DCU stages (direct connection, no internal skid buffers)
    logic [DATA_WIDTH-1:0] chain_data  [NUM_UNITS:0];
    logic [TAG_WIDTH-1:0]  chain_tag   [NUM_UNITS:0];
    logic                  chain_valid [NUM_UNITS:0];
    logic                  chain_ready [NUM_UNITS:0];

    logic [DATA_WIDTH-1:0] fifo_wdata [NUM_UNITS-1:0];
    logic                  fifo_wen   [NUM_UNITS-1:0];
    logic                  fifo_full  [NUM_UNITS-1:0];
    logic                  fifo_empty [NUM_UNITS-1:0];

    // Skid buffer at tile input breaks the inter-tile timing path
    skid_buffer #(
        .WIDTH(DATA_WIDTH + TAG_WIDTH)
    ) u_skid_in (
        .clk(clk),
        .rst_n(~reset),
        .s_data({s_data, s_tag}),
        .s_valid(s_valid),
        .s_ready(s_ready),
        .m_data({chain_data[0], chain_tag[0]}),
        .m_valid(chain_valid[0]),
        .m_ready(chain_ready[0])
    );

    // Skid buffer at tile output breaks the inter-tile ready/valid/data path
    skid_buffer #(
        .WIDTH(DATA_WIDTH + TAG_WIDTH)
    ) u_skid_out (
        .clk(clk),
        .rst_n(~reset),
        .s_data({chain_data[NUM_UNITS], chain_tag[NUM_UNITS]}),
        .s_valid(chain_valid[NUM_UNITS]),
        .s_ready(chain_ready[NUM_UNITS]),
        .m_data({m_data, m_tag}),
        .m_valid(m_valid),
        .m_ready(m_ready)
    );

    genvar i;
    generate
        for (i = 0; i < NUM_UNITS; i = i + 1) begin : gen_dcu
            daisy_chain_loader #(
                .DATA_WIDTH(DATA_WIDTH),
                .TAG_WIDTH(TAG_WIDTH),
                .ID_WIDTH(ID_WIDTH)
            ) u_dcu (
                .clk(clk),
                .rst_n(~reset),
                .id(id_reg + ID_WIDTH'(i)),
                .s_data(chain_data[i]),
                .s_tag(chain_tag[i]),
                .s_valid(chain_valid[i]),
                .s_ready(chain_ready[i]),
                .m_data(chain_data[i+1]),
                .m_tag(chain_tag[i+1]),
                .m_valid(chain_valid[i+1]),
                .m_ready(chain_ready[i+1]),
                .fifo_wdata(fifo_wdata[i]),
                .fifo_wen(fifo_wen[i]),
                .fifo_full(fifo_full[i])
            );

            assign fifo_full[i] = fifo_full_internal[i];
        end
    endgenerate

    // rd_en pipeline: input feeds unit 0, registered between each subsequent unit
    // Register rd_en at tile input to break inter-tile timing path
    logic rd_en_pipe [NUM_UNITS:0];
    always_ff @(posedge clk) begin
        if (reset)
            rd_en_pipe[0] <= 1'b0;
        else
            rd_en_pipe[0] <= rd_en;
    end

    generate
        for (i = 0; i < NUM_UNITS; i = i + 1) begin : gen_rd_en_pipe
            always_ff @(posedge clk) begin
                if (reset)
                    rd_en_pipe[i+1] <= 1'b0;
                else
                    rd_en_pipe[i+1] <= rd_en_pipe[i];
            end
        end
    endgenerate

    // Output rd_en is the last pipeline stage (already registered)
    assign rd_en_out = rd_en_pipe[NUM_UNITS];

    // Build rd_en array for fifo_tile from pipeline
    logic rd_en_arr [NUM_UNITS-1:0];
    generate
        for (i = 0; i < NUM_UNITS; i = i + 1) begin : gen_rd_en_arr
            assign rd_en_arr[i] = rd_en_pipe[i];
        end
    endgenerate

    logic [FIFO_PTR_SIZE:0] fifo_count [NUM_UNITS-1:0];
    logic                   fifo_full_internal [NUM_UNITS-1:0];

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
        .full(fifo_full_internal)
    );

    generate
        for (i = 0; i < NUM_UNITS; i = i + 1) begin : gen_dout_valid
            always_ff @(posedge clk) begin
                if (reset) begin
                    dout_valid[i] <= 1'b0;
                end else begin
                    dout_valid[i] <= ~fifo_empty[i] && rd_en_arr[i];
                end
            end
        end
    endgenerate

endmodule
