// S2MM AXI-Lite Slave Wrapper - Pure Verilog wrapper for SystemVerilog module

module s2mm_axilite_slave_wrapper #(
    parameter ADDR_WIDTH = 6,
    parameter DATA_WIDTH = 32
) (
    input  wire                    clk,
    input  wire                    rst_n,

    // AXI-Lite Slave Interface
    input  wire [ADDR_WIDTH-1:0]   s_axi_awaddr,
    input  wire                    s_axi_awvalid,
    output wire                    s_axi_awready,

    input  wire [DATA_WIDTH-1:0]   s_axi_wdata,
    input  wire [DATA_WIDTH/8-1:0] s_axi_wstrb,
    input  wire                    s_axi_wvalid,
    output wire                    s_axi_wready,

    output wire [1:0]              s_axi_bresp,
    output wire                    s_axi_bvalid,
    input  wire                    s_axi_bready,

    input  wire [ADDR_WIDTH-1:0]   s_axi_araddr,
    input  wire                    s_axi_arvalid,
    output wire                    s_axi_arready,

    output wire [DATA_WIDTH-1:0]   s_axi_rdata,
    output wire [1:0]              s_axi_rresp,
    output wire                    s_axi_rvalid,
    input  wire                    s_axi_rready,

    // Interrupt output
    output wire                    interrupt,

    // Control outputs
    output wire                    start,
    output wire [31:0]             dst_addr,
    output wire [22:0]             transfer_length,

    // Status inputs
    input  wire                    done,
    input  wire                    busy,
    input  wire                    error
);

    s2mm_axilite_slave #(
        .ADDR_WIDTH(ADDR_WIDTH),
        .DATA_WIDTH(DATA_WIDTH)
    ) inst (
        .clk             (clk),
        .rst_n           (rst_n),

        .s_axi_awaddr    (s_axi_awaddr),
        .s_axi_awvalid   (s_axi_awvalid),
        .s_axi_awready   (s_axi_awready),

        .s_axi_wdata     (s_axi_wdata),
        .s_axi_wstrb     (s_axi_wstrb),
        .s_axi_wvalid    (s_axi_wvalid),
        .s_axi_wready    (s_axi_wready),

        .s_axi_bresp     (s_axi_bresp),
        .s_axi_bvalid    (s_axi_bvalid),
        .s_axi_bready    (s_axi_bready),

        .s_axi_araddr    (s_axi_araddr),
        .s_axi_arvalid   (s_axi_arvalid),
        .s_axi_arready   (s_axi_arready),

        .s_axi_rdata     (s_axi_rdata),
        .s_axi_rresp     (s_axi_rresp),
        .s_axi_rvalid    (s_axi_rvalid),
        .s_axi_rready    (s_axi_rready),

        .interrupt        (interrupt),

        .start           (start),
        .dst_addr        (dst_addr),
        .transfer_length (transfer_length),

        .done            (done),
        .busy            (busy),
        .error           (error)
    );

endmodule
