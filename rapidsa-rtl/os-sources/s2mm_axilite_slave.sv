// S2MM AXI-Lite Slave Endpoint
// Follows Vitis HLS s_axi_control register convention
//
// Register Map:
//   0x00  Control/Status  - Bit 0: ap_start (W1S), Bit 1: ap_done (R/COR),
//                           Bit 2: ap_idle (R), Bit 3: ap_error (R)
//   0x04  GIE             - Bit 0: Global Interrupt Enable
//   0x08  IER             - Bit 0: done IE, Bit 1: ready IE
//   0x0C  ISR             - Bit 0: done IS (W1C), Bit 1: ready IS (W1C)
//   0x10  dst_addr        - Destination address [31:0]
//   0x14  dst_addr_hi     - Destination address [63:32] (reserved)
//   0x18  transfer_length - Transfer length in bytes [22:0]

module s2mm_axilite_slave #(
    parameter ADDR_WIDTH = 6,
    parameter DATA_WIDTH = 32
) (
    input  logic                    clk,
    input  logic                    rst_n,

    // AXI-Lite Slave Interface
    input  logic [ADDR_WIDTH-1:0]   s_axi_awaddr,
    input  logic                    s_axi_awvalid,
    output logic                    s_axi_awready,

    input  logic [DATA_WIDTH-1:0]   s_axi_wdata,
    input  logic [DATA_WIDTH/8-1:0] s_axi_wstrb,
    input  logic                    s_axi_wvalid,
    output logic                    s_axi_wready,

    output logic [1:0]              s_axi_bresp,
    output logic                    s_axi_bvalid,
    input  logic                    s_axi_bready,

    input  logic [ADDR_WIDTH-1:0]   s_axi_araddr,
    input  logic                    s_axi_arvalid,
    output logic                    s_axi_arready,

    output logic [DATA_WIDTH-1:0]   s_axi_rdata,
    output logic [1:0]              s_axi_rresp,
    output logic                    s_axi_rvalid,
    input  logic                    s_axi_rready,

    // Interrupt output
    output logic                    interrupt,

    // Control outputs (to S2MM channel)
    output logic                    start,
    output logic [31:0]             dst_addr,
    output logic [22:0]             transfer_length,

    // Status inputs (from S2MM channel)
    input  logic                    done,
    input  logic                    busy,
    input  logic                    error
);

    // =========================================================================
    // Register offsets
    // =========================================================================
    localparam ADDR_CTRL   = 6'h00;
    localparam ADDR_GIE    = 6'h04;
    localparam ADDR_IER    = 6'h08;
    localparam ADDR_ISR    = 6'h0C;
    localparam ADDR_DST    = 6'h10;
    localparam ADDR_DST_HI = 6'h14;
    localparam ADDR_LEN    = 6'h18;

    // =========================================================================
    // Internal registers
    // =========================================================================
    logic        ap_start;
    logic        ap_done;
    logic        ap_idle;

    logic        gie;
    logic [1:0]  ier;
    logic [1:0]  isr;

    logic [31:0] dst_addr_reg;
    logic [31:0] dst_addr_hi_reg;
    logic [22:0] transfer_length_reg;

    // =========================================================================
    // AXI-Lite write channel
    // =========================================================================
    logic        aw_handshake;
    logic        w_handshake;
    logic [ADDR_WIDTH-1:0] waddr;
    logic [DATA_WIDTH-1:0] wdata;
    logic [DATA_WIDTH/8-1:0] wstrb;

    // Accept AW and W independently, hold until both received
    logic aw_done, w_done;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            aw_done <= 1'b0;
            w_done  <= 1'b0;
            waddr   <= '0;
            wdata   <= '0;
            wstrb   <= '0;
        end else begin
            if (s_axi_awvalid && s_axi_awready) begin
                aw_done <= 1'b1;
                waddr   <= s_axi_awaddr;
            end
            if (s_axi_wvalid && s_axi_wready) begin
                w_done <= 1'b1;
                wdata  <= s_axi_wdata;
                wstrb  <= s_axi_wstrb;
            end
            if (aw_done && w_done) begin
                aw_done <= 1'b0;
                w_done  <= 1'b0;
            end
        end
    end

    assign s_axi_awready = !aw_done;
    assign s_axi_wready  = !w_done;
    assign aw_handshake  = aw_done && w_done;

    // Write response
    logic bvalid_reg;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            bvalid_reg <= 1'b0;
        else if (aw_handshake)
            bvalid_reg <= 1'b1;
        else if (s_axi_bready)
            bvalid_reg <= 1'b0;
    end

    assign s_axi_bvalid = bvalid_reg;
    assign s_axi_bresp  = 2'b00; // OKAY

    // =========================================================================
    // AXI-Lite read channel
    // =========================================================================
    logic [ADDR_WIDTH-1:0] raddr;
    logic rvalid_reg;
    logic [DATA_WIDTH-1:0] rdata_reg;
    logic read_req;

    assign read_req = s_axi_arvalid && s_axi_arready;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            rvalid_reg <= 1'b0;
            rdata_reg  <= '0;
            raddr      <= '0;
        end else begin
            if (read_req) begin
                raddr <= s_axi_araddr;
                rvalid_reg <= 1'b1;
                case (s_axi_araddr)
                    ADDR_CTRL:   rdata_reg <= {28'b0, error, ap_idle, ap_done, ap_start};
                    ADDR_GIE:    rdata_reg <= {31'b0, gie};
                    ADDR_IER:    rdata_reg <= {30'b0, ier};
                    ADDR_ISR:    rdata_reg <= {30'b0, isr};
                    ADDR_DST:    rdata_reg <= dst_addr_reg;
                    ADDR_DST_HI: rdata_reg <= dst_addr_hi_reg;
                    ADDR_LEN:    rdata_reg <= {9'b0, transfer_length_reg};
                    default:     rdata_reg <= '0;
                endcase
            end else if (s_axi_rready) begin
                rvalid_reg <= 1'b0;
            end
        end
    end

    assign s_axi_arready = !rvalid_reg;
    assign s_axi_rvalid  = rvalid_reg;
    assign s_axi_rdata   = rdata_reg;
    assign s_axi_rresp   = 2'b00; // OKAY

    // =========================================================================
    // ap_done clear-on-read
    // =========================================================================
    logic ap_done_cor;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            ap_done_cor <= 1'b0;
        else if (read_req && s_axi_araddr == ADDR_CTRL && ap_done)
            ap_done_cor <= 1'b1;  // Will clear ap_done next cycle
        else
            ap_done_cor <= 1'b0;
    end

    // =========================================================================
    // Register write logic
    // =========================================================================
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            ap_start            <= 1'b0;
            ap_done             <= 1'b0;
            ap_idle             <= 1'b1;
            gie                 <= 1'b0;
            ier                 <= 2'b0;
            isr                 <= 2'b0;
            dst_addr_reg        <= '0;
            dst_addr_hi_reg     <= '0;
            transfer_length_reg <= '0;
        end else begin
            // ap_start: pulse for one cycle
            if (ap_start)
                ap_start <= 1'b0;

            // ap_done: set on done rising edge, clear on read
            if (done)
                ap_done <= 1'b1;
            else if (ap_done_cor)
                ap_done <= 1'b0;

            // ap_idle
            ap_idle <= ~busy;

            // ISR: set on done/ready events
            if (done)
                isr[0] <= 1'b1;

            // Register writes
            if (aw_handshake) begin
                case (waddr)
                    ADDR_CTRL: begin
                        if (wstrb[0] && wdata[0])
                            ap_start <= 1'b1;
                    end
                    ADDR_GIE: begin
                        if (wstrb[0])
                            gie <= wdata[0];
                    end
                    ADDR_IER: begin
                        if (wstrb[0])
                            ier <= wdata[1:0];
                    end
                    ADDR_ISR: begin
                        // Write-1-to-clear
                        if (wstrb[0])
                            isr <= isr & ~wdata[1:0];
                    end
                    ADDR_DST: begin
                        if (wstrb[0]) dst_addr_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) dst_addr_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) dst_addr_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) dst_addr_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_DST_HI: begin
                        if (wstrb[0]) dst_addr_hi_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) dst_addr_hi_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) dst_addr_hi_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) dst_addr_hi_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_LEN: begin
                        if (wstrb[0]) transfer_length_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) transfer_length_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) transfer_length_reg[22:16] <= wdata[22:16];
                    end
                    default: ;
                endcase
            end
        end
    end

    // =========================================================================
    // Output assignments
    // =========================================================================
    assign start           = ap_start;
    assign dst_addr        = dst_addr_reg;
    assign transfer_length = transfer_length_reg;

    // =========================================================================
    // Interrupt
    // =========================================================================
    assign interrupt = gie & |(ier & isr);

endmodule
