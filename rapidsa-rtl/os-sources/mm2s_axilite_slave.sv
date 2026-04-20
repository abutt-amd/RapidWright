// MM2S AXI4 Slave Endpoint
// Follows Vitis HLS s_axi_control register convention
// Accepts AXI4 interface but only handles single-beat transactions (no bursts)
//
// Register Map:
//   0x00  Control/Status  - Bit 0: ap_start (W1S), Bit 1: ap_done (R/COR),
//                           Bit 2: ap_idle (R)
//   0x04  GIE             - Bit 0: Global Interrupt Enable
//   0x08  IER             - Bit 0: done IE, Bit 1: ready IE
//   0x0C  ISR             - Bit 0: done IS (W1C), Bit 1: ready IS (W1C)
//   0x10  src_addr_a      - Source address A [31:0]
//   0x14  src_addr_a_hi   - Source address A [63:32] (reserved)
//   0x18  transfer_len_a  - Transfer length A in bytes [22:0]
//   0x1C  (reserved)
//   0x20  src_addr_b      - Source address B [31:0]
//   0x24  src_addr_b_hi   - Source address B [63:32] (reserved)
//   0x28  transfer_len_b  - Transfer length B in bytes [22:0]

module mm2s_axilite_slave #(
    parameter ADDR_WIDTH = 6,
    parameter DATA_WIDTH = 32,
    parameter ID_WIDTH   = 2
) (
    input  logic                    clk,
    input  logic                    rst_n,

    // AXI4 Slave Interface
    input  logic [ID_WIDTH-1:0]     s_axi_awid,
    input  logic [ADDR_WIDTH-1:0]   s_axi_awaddr,
    input  logic [7:0]              s_axi_awlen,
    input  logic [2:0]              s_axi_awsize,
    input  logic [1:0]              s_axi_awburst,
    input  logic                    s_axi_awvalid,
    output logic                    s_axi_awready,

    input  logic [DATA_WIDTH-1:0]   s_axi_wdata,
    input  logic [DATA_WIDTH/8-1:0] s_axi_wstrb,
    input  logic                    s_axi_wlast,
    input  logic                    s_axi_wvalid,
    output logic                    s_axi_wready,

    output logic [ID_WIDTH-1:0]     s_axi_bid,
    output logic [1:0]              s_axi_bresp,
    output logic                    s_axi_bvalid,
    input  logic                    s_axi_bready,

    input  logic [ID_WIDTH-1:0]     s_axi_arid,
    input  logic [ADDR_WIDTH-1:0]   s_axi_araddr,
    input  logic [7:0]              s_axi_arlen,
    input  logic [2:0]              s_axi_arsize,
    input  logic [1:0]              s_axi_arburst,
    input  logic                    s_axi_arvalid,
    output logic                    s_axi_arready,

    output logic [ID_WIDTH-1:0]     s_axi_rid,
    output logic [DATA_WIDTH-1:0]   s_axi_rdata,
    output logic [1:0]              s_axi_rresp,
    output logic                    s_axi_rlast,
    output logic                    s_axi_rvalid,
    input  logic                    s_axi_rready,

    // Interrupt output
    output logic                    interrupt,

    // Control outputs
    output logic                    start,
    output logic [31:0]             src_addr_a,
    output logic [22:0]             transfer_length_a,
    output logic [31:0]             src_addr_b,
    output logic [22:0]             transfer_length_b,

    // Status inputs
    input  logic                    done,
    input  logic                    busy
);

    // =========================================================================
    // Register offsets
    // =========================================================================
    localparam ADDR_CTRL       = 6'h00;
    localparam ADDR_GIE        = 6'h04;
    localparam ADDR_IER        = 6'h08;
    localparam ADDR_ISR        = 6'h0C;
    localparam ADDR_SRC_A      = 6'h10;
    localparam ADDR_SRC_A_HI   = 6'h14;
    localparam ADDR_LEN_A      = 6'h18;
    localparam ADDR_SRC_B      = 6'h20;
    localparam ADDR_SRC_B_HI   = 6'h24;
    localparam ADDR_LEN_B      = 6'h28;

    // =========================================================================
    // Internal registers
    // =========================================================================
    logic        ap_start;
    logic        ap_done;
    logic        ap_idle;

    logic        gie;
    logic [1:0]  ier;
    logic [1:0]  isr;

    logic [31:0] src_addr_a_reg;
    logic [31:0] src_addr_a_hi_reg;
    logic [22:0] transfer_length_a_reg;
    logic [31:0] src_addr_b_reg;
    logic [31:0] src_addr_b_hi_reg;
    logic [22:0] transfer_length_b_reg;

    // =========================================================================
    // AXI4 write channel (single-beat only)
    // =========================================================================
    logic        aw_handshake;
    logic [ADDR_WIDTH-1:0] waddr;
    logic [DATA_WIDTH-1:0] wdata;
    logic [DATA_WIDTH/8-1:0] wstrb;
    logic [ID_WIDTH-1:0] wid;

    logic aw_done, w_done;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            aw_done <= 1'b0;
            w_done  <= 1'b0;
            waddr   <= '0;
            wdata   <= '0;
            wstrb   <= '0;
            wid     <= '0;
        end else begin
            if (s_axi_awvalid && s_axi_awready) begin
                aw_done <= 1'b1;
                waddr   <= s_axi_awaddr;
                wid     <= s_axi_awid;
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
    logic [ID_WIDTH-1:0] bid_reg;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            bvalid_reg <= 1'b0;
            bid_reg    <= '0;
        end else if (aw_handshake) begin
            bvalid_reg <= 1'b1;
            bid_reg    <= wid;
        end else if (s_axi_bready) begin
            bvalid_reg <= 1'b0;
        end
    end

    assign s_axi_bvalid = bvalid_reg;
    assign s_axi_bresp  = 2'b00;
    assign s_axi_bid    = bid_reg;

    // =========================================================================
    // AXI4 read channel (single-beat only)
    // =========================================================================
    logic rvalid_reg;
    logic [DATA_WIDTH-1:0] rdata_reg;
    logic [ID_WIDTH-1:0] rid_reg;
    logic [ADDR_WIDTH-1:0] araddr_reg;
    logic [ID_WIDTH-1:0] arid_reg;
    logic ar_pending;
    logic read_req;

    assign read_req = s_axi_arvalid && s_axi_arready;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            rvalid_reg <= 1'b0;
            rdata_reg  <= '0;
            rid_reg    <= '0;
            araddr_reg <= '0;
            arid_reg   <= '0;
            ar_pending <= 1'b0;
        end else begin
            if (read_req) begin
                araddr_reg <= s_axi_araddr;
                arid_reg   <= s_axi_arid;
                ar_pending <= 1'b1;
            end else if (ar_pending) begin
                rid_reg    <= arid_reg;
                rvalid_reg <= 1'b1;
                ar_pending <= 1'b0;
                case (araddr_reg)
                    ADDR_CTRL:     rdata_reg <= {29'b0, ap_idle, ap_done, ap_start};
                    ADDR_GIE:      rdata_reg <= {31'b0, gie};
                    ADDR_IER:      rdata_reg <= {30'b0, ier};
                    ADDR_ISR:      rdata_reg <= {30'b0, isr};
                    ADDR_SRC_A:    rdata_reg <= src_addr_a_reg;
                    ADDR_SRC_A_HI: rdata_reg <= src_addr_a_hi_reg;
                    ADDR_LEN_A:    rdata_reg <= {9'b0, transfer_length_a_reg};
                    ADDR_SRC_B:    rdata_reg <= src_addr_b_reg;
                    ADDR_SRC_B_HI: rdata_reg <= src_addr_b_hi_reg;
                    ADDR_LEN_B:    rdata_reg <= {9'b0, transfer_length_b_reg};
                    default:       rdata_reg <= '0;
                endcase
            end else if (s_axi_rready) begin
                rvalid_reg <= 1'b0;
            end
        end
    end

    assign s_axi_arready = !ar_pending && !rvalid_reg;
    assign s_axi_rvalid  = rvalid_reg;
    assign s_axi_rdata   = rdata_reg;
    assign s_axi_rresp   = 2'b00;
    assign s_axi_rid     = rid_reg;
    assign s_axi_rlast   = 1'b1;

    // =========================================================================
    // ap_done clear-on-read
    // =========================================================================
    logic ap_done_cor;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            ap_done_cor <= 1'b0;
        else if (read_req && s_axi_araddr == ADDR_CTRL && ap_done)
            ap_done_cor <= 1'b1;
        else
            ap_done_cor <= 1'b0;
    end

    // =========================================================================
    // Register write logic
    // =========================================================================
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            ap_start              <= 1'b0;
            ap_done               <= 1'b0;
            ap_idle               <= 1'b1;
            gie                   <= 1'b0;
            ier                   <= 2'b0;
            isr                   <= 2'b0;
            src_addr_a_reg        <= '0;
            src_addr_a_hi_reg     <= '0;
            transfer_length_a_reg <= '0;
            src_addr_b_reg        <= '0;
            src_addr_b_hi_reg     <= '0;
            transfer_length_b_reg <= '0;
        end else begin
            if (ap_start)
                ap_start <= 1'b0;

            if (done)
                ap_done <= 1'b1;
            else if (ap_done_cor)
                ap_done <= 1'b0;

            ap_idle <= ~busy;

            if (done)
                isr[0] <= 1'b1;

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
                        if (wstrb[0])
                            isr <= isr & ~wdata[1:0];
                    end
                    ADDR_SRC_A: begin
                        if (wstrb[0]) src_addr_a_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) src_addr_a_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) src_addr_a_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) src_addr_a_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_SRC_A_HI: begin
                        if (wstrb[0]) src_addr_a_hi_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) src_addr_a_hi_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) src_addr_a_hi_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) src_addr_a_hi_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_LEN_A: begin
                        if (wstrb[0]) transfer_length_a_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) transfer_length_a_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) transfer_length_a_reg[22:16] <= wdata[22:16];
                    end
                    ADDR_SRC_B: begin
                        if (wstrb[0]) src_addr_b_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) src_addr_b_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) src_addr_b_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) src_addr_b_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_SRC_B_HI: begin
                        if (wstrb[0]) src_addr_b_hi_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) src_addr_b_hi_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) src_addr_b_hi_reg[23:16] <= wdata[23:16];
                        if (wstrb[3]) src_addr_b_hi_reg[31:24] <= wdata[31:24];
                    end
                    ADDR_LEN_B: begin
                        if (wstrb[0]) transfer_length_b_reg[ 7: 0] <= wdata[ 7: 0];
                        if (wstrb[1]) transfer_length_b_reg[15: 8] <= wdata[15: 8];
                        if (wstrb[2]) transfer_length_b_reg[22:16] <= wdata[22:16];
                    end
                    default: ;
                endcase
            end
        end
    end

    // =========================================================================
    // Output assignments
    // =========================================================================
    assign start             = ap_start;
    assign src_addr_a        = src_addr_a_reg;
    assign transfer_length_a = transfer_length_a_reg;
    assign src_addr_b        = src_addr_b_reg;
    assign transfer_length_b = transfer_length_b_reg;

    // =========================================================================
    // Interrupt
    // =========================================================================
    assign interrupt = gie & |(ier & isr);

endmodule
