// AXI DMA MM2S Controller Wrapper - Pure Verilog wrapper for SystemVerilog module
// Controls MM2S (Memory-Mapped to Stream) channel in direct register mode

module axi_dma_mm2s_ctrl_wrapper #(
    parameter ADDR_WIDTH = 32,
    parameter DATA_WIDTH = 32
) (
    input  wire                    clk,
    input  wire                    rst_n,

    // Control interface
    input  wire                    start,
    input  wire [ADDR_WIDTH-1:0]   src_addr,
    input  wire [22:0]             transfer_length,

    output wire                    done,
    output wire                    error,
    output wire                    busy,

    // AXI-Lite Master Interface
    // Write address channel
    output wire [ADDR_WIDTH-1:0]   m_axi_lite_awaddr,
    output wire [2:0]              m_axi_lite_awprot,
    output wire                    m_axi_lite_awvalid,
    input  wire                    m_axi_lite_awready,

    // Write data channel
    output wire [DATA_WIDTH-1:0]   m_axi_lite_wdata,
    output wire [DATA_WIDTH/8-1:0] m_axi_lite_wstrb,
    output wire                    m_axi_lite_wvalid,
    input  wire                    m_axi_lite_wready,

    // Write response channel
    input  wire [1:0]              m_axi_lite_bresp,
    input  wire                    m_axi_lite_bvalid,
    output wire                    m_axi_lite_bready,

    // Read address channel
    output wire [ADDR_WIDTH-1:0]   m_axi_lite_araddr,
    output wire [2:0]              m_axi_lite_arprot,
    output wire                    m_axi_lite_arvalid,
    input  wire                    m_axi_lite_arready,

    // Read data channel
    input  wire [DATA_WIDTH-1:0]   m_axi_lite_rdata,
    input  wire [1:0]              m_axi_lite_rresp,
    input  wire                    m_axi_lite_rvalid,
    output wire                    m_axi_lite_rready
);

    axi_dma_mm2s_ctrl #(
        .ADDR_WIDTH(ADDR_WIDTH),
        .DATA_WIDTH(DATA_WIDTH)
    ) u_mm2s_ctrl (
        .clk             (clk),
        .rst_n           (rst_n),

        .start           (start),
        .src_addr        (src_addr),
        .transfer_length (transfer_length),

        .done            (done),
        .error           (error),
        .busy            (busy),

        .m_axi_lite_awaddr    (m_axi_lite_awaddr),
        .m_axi_lite_awprot    (m_axi_lite_awprot),
        .m_axi_lite_awvalid   (m_axi_lite_awvalid),
        .m_axi_lite_awready   (m_axi_lite_awready),

        .m_axi_lite_wdata     (m_axi_lite_wdata),
        .m_axi_lite_wstrb     (m_axi_lite_wstrb),
        .m_axi_lite_wvalid    (m_axi_lite_wvalid),
        .m_axi_lite_wready    (m_axi_lite_wready),

        .m_axi_lite_bresp     (m_axi_lite_bresp),
        .m_axi_lite_bvalid    (m_axi_lite_bvalid),
        .m_axi_lite_bready    (m_axi_lite_bready),

        .m_axi_lite_araddr    (m_axi_lite_araddr),
        .m_axi_lite_arprot    (m_axi_lite_arprot),
        .m_axi_lite_arvalid   (m_axi_lite_arvalid),
        .m_axi_lite_arready   (m_axi_lite_arready),

        .m_axi_lite_rdata     (m_axi_lite_rdata),
        .m_axi_lite_rresp     (m_axi_lite_rresp),
        .m_axi_lite_rvalid    (m_axi_lite_rvalid),
        .m_axi_lite_rready    (m_axi_lite_rready)
    );

endmodule
