// AXI DMA MM2S Controller - AXI-Lite Master for Xilinx AXI DMA IP
// Controls MM2S (Memory-Mapped to Stream) channel in direct register mode
// Note: Xilinx AXI DMA requires AWVALID and WVALID asserted simultaneously

module axi_dma_mm2s_ctrl #(
    parameter ADDR_WIDTH = 32,
    parameter DATA_WIDTH = 32
) (
    input  logic                    clk,
    input  logic                    rst_n,

    // Control interface
    input  logic                    start,           // Start MM2S transfer
    input  logic [ADDR_WIDTH-1:0]   src_addr,        // Source address
    input  logic [22:0]             transfer_length, // Transfer length in bytes (max 8MB)

    output logic                    done,            // Transfer complete
    output logic                    error,           // Error occurred
    output logic                    busy,            // Controller busy

    // AXI-Lite Master Interface
    // Write address channel
    output logic [ADDR_WIDTH-1:0]   m_axi_lite_awaddr,
    output logic [2:0]              m_axi_lite_awprot,
    output logic                    m_axi_lite_awvalid,
    input  logic                    m_axi_lite_awready,

    // Write data channel
    output logic [DATA_WIDTH-1:0]   m_axi_lite_wdata,
    output logic [DATA_WIDTH/8-1:0] m_axi_lite_wstrb,
    output logic                    m_axi_lite_wvalid,
    input  logic                    m_axi_lite_wready,

    // Write response channel
    input  logic [1:0]              m_axi_lite_bresp,
    input  logic                    m_axi_lite_bvalid,
    output logic                    m_axi_lite_bready,

    // Read address channel
    output logic [ADDR_WIDTH-1:0]   m_axi_lite_araddr,
    output logic [2:0]              m_axi_lite_arprot,
    output logic                    m_axi_lite_arvalid,
    input  logic                    m_axi_lite_arready,

    // Read data channel
    input  logic [DATA_WIDTH-1:0]   m_axi_lite_rdata,
    input  logic [1:0]              m_axi_lite_rresp,
    input  logic                    m_axi_lite_rvalid,
    output logic                    m_axi_lite_rready
);

    // =========================================================================
    // AXI DMA MM2S Register Offsets
    // =========================================================================
    localparam OFFSET_MM2S_DMACR  = 12'h000;  // MM2S DMA Control
    localparam OFFSET_MM2S_DMASR  = 12'h004;  // MM2S DMA Status
    localparam OFFSET_MM2S_SA     = 12'h018;  // MM2S Source Address
    localparam OFFSET_MM2S_SA_MSB = 12'h01C;  // MM2S Source Address MSB
    localparam OFFSET_MM2S_LENGTH = 12'h028;  // MM2S Transfer Length

    // =========================================================================
    // Control/Status Register Bits
    // =========================================================================
    localparam DMACR_RS         = 0;   // Run/Stop
    localparam DMACR_RESET      = 2;   // Soft Reset
    localparam DMACR_IOC_IRQEN  = 12;  // Interrupt on Complete Enable
    localparam DMACR_ERR_IRQEN  = 14;  // Error Interrupt Enable

    localparam DMASR_HALTED     = 0;   // DMA Halted
    localparam DMASR_IDLE       = 1;   // DMA Idle
    localparam DMASR_DMAINTERR  = 4;   // DMA Internal Error
    localparam DMASR_DMASLVERR  = 5;   // DMA Slave Error
    localparam DMASR_DMADECERR  = 6;   // DMA Decode Error
    localparam DMASR_IOC_IRQ    = 12;  // Interrupt on Complete
    localparam DMASR_ERR_IRQ    = 14;  // Error Interrupt

    // =========================================================================
    // State Machine
    // =========================================================================
    typedef enum logic [3:0] {
        ST_IDLE,
        ST_WRITE,        // Assert both AWVALID and WVALID together
        ST_WRITE_RESP,
        ST_READ_ADDR,
        ST_READ_DATA,
        ST_SET_CTRL,
        ST_SET_ADDR,
        ST_SET_LEN,
        ST_POLL,
        ST_DONE
    } state_t;

    state_t state, next_state;
    state_t return_state;

    // =========================================================================
    // Internal Registers
    // =========================================================================
    logic [ADDR_WIDTH-1:0] axi_addr;
    logic [DATA_WIDTH-1:0] axi_wdata;
    logic [DATA_WIDTH-1:0] axi_rdata;

    logic [ADDR_WIDTH-1:0] src_addr_reg;
    logic [22:0]           length_reg;

    // Track which channels have completed handshake
    logic aw_done;
    logic w_done;

    // =========================================================================
    // AXI-Lite Default Assignments
    // =========================================================================
    assign m_axi_lite_awprot = 3'b000;
    assign m_axi_lite_arprot = 3'b000;
    assign m_axi_lite_wstrb  = {(DATA_WIDTH/8){1'b1}};

    // =========================================================================
    // State Machine - Next State Logic
    // =========================================================================
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            state <= ST_IDLE;
        else
            state <= next_state;
    end

    always_comb begin
        next_state = state;

        case (state)
            ST_IDLE: begin
                if (start)
                    next_state = ST_SET_CTRL;
            end

            ST_WRITE: begin
                // Wait for both AW and W channels to complete
                if ((aw_done || (m_axi_lite_awready && m_axi_lite_awvalid)) &&
                    (w_done  || (m_axi_lite_wready  && m_axi_lite_wvalid)))
                    next_state = ST_WRITE_RESP;
            end

            ST_WRITE_RESP: begin
                if (m_axi_lite_bvalid)
                    next_state = return_state;
            end

            ST_READ_ADDR: begin
                if (m_axi_lite_arready && m_axi_lite_arvalid)
                    next_state = ST_READ_DATA;
            end

            ST_READ_DATA: begin
                if (m_axi_lite_rvalid)
                    next_state = return_state;
            end

            ST_SET_CTRL: next_state = ST_WRITE;
            ST_SET_ADDR: next_state = ST_WRITE;
            ST_SET_LEN:  next_state = ST_WRITE;
            ST_POLL:     next_state = ST_READ_ADDR;
            ST_DONE:     next_state = ST_IDLE;

            default: next_state = ST_IDLE;
        endcase
    end

    // =========================================================================
    // AW/W Channel Handshake Tracking
    // =========================================================================
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            aw_done <= 1'b0;
            w_done  <= 1'b0;
        end else begin
            if (state == ST_WRITE) begin
                // Track individual channel completions
                if (m_axi_lite_awready && m_axi_lite_awvalid)
                    aw_done <= 1'b1;
                if (m_axi_lite_wready && m_axi_lite_wvalid)
                    w_done <= 1'b1;
            end else begin
                // Reset for next transaction
                aw_done <= 1'b0;
                w_done  <= 1'b0;
            end
        end
    end

    // =========================================================================
    // Control Logic
    // =========================================================================
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            axi_addr     <= '0;
            axi_wdata    <= '0;
            axi_rdata    <= '0;
            return_state <= ST_IDLE;
            src_addr_reg <= '0;
            length_reg   <= '0;
            done         <= 1'b0;
            error        <= 1'b0;
        end else begin
            // Clear flags when starting new transfer
            if (state == ST_IDLE && start) begin
                done         <= 1'b0;
                error        <= 1'b0;
                src_addr_reg <= src_addr;
                length_reg   <= transfer_length;
            end

            // Capture read data
            if (state == ST_READ_DATA && m_axi_lite_rvalid)
                axi_rdata <= m_axi_lite_rdata;

            // State-specific logic
            case (state)
                ST_SET_CTRL: begin
                    axi_addr     <= OFFSET_MM2S_DMACR;
                    axi_wdata    <= (1 << DMACR_RS);
                    return_state <= ST_SET_ADDR;
                end

                ST_SET_ADDR: begin
                    axi_addr     <= OFFSET_MM2S_SA;
                    axi_wdata    <= src_addr_reg;
                    return_state <= ST_SET_LEN;
                end

                ST_SET_LEN: begin
                    axi_addr     <= OFFSET_MM2S_LENGTH;
                    axi_wdata    <= {9'b0, length_reg};
                    return_state <= ST_POLL;
                end

                ST_POLL: begin
                    axi_addr <= OFFSET_MM2S_DMASR;
                    if (return_state == ST_POLL) begin
                        if (axi_rdata[DMASR_IDLE]) begin
                            done         <= 1'b1;
                            return_state <= ST_DONE;
                        end else if (axi_rdata[DMASR_DMAINTERR] ||
                                     axi_rdata[DMASR_DMASLVERR] ||
                                     axi_rdata[DMASR_DMADECERR]) begin
                            error        <= 1'b1;
                            return_state <= ST_DONE;
                        end else begin
                            return_state <= ST_POLL;
                        end
                    end else begin
                        return_state <= ST_POLL;
                    end
                end

                default: ;
            endcase
        end
    end

    // =========================================================================
    // AXI-Lite Output Signals
    // =========================================================================
    assign m_axi_lite_awaddr  = axi_addr;
    // Assert AWVALID during ST_WRITE until handshake completes
    assign m_axi_lite_awvalid = (state == ST_WRITE) && !aw_done;

    assign m_axi_lite_wdata  = axi_wdata;
    // Assert WVALID during ST_WRITE until handshake completes
    assign m_axi_lite_wvalid = (state == ST_WRITE) && !w_done;

    assign m_axi_lite_bready = (state == ST_WRITE_RESP);

    assign m_axi_lite_araddr  = axi_addr;
    assign m_axi_lite_arvalid = (state == ST_READ_ADDR);

    assign m_axi_lite_rready = (state == ST_READ_DATA);

    assign busy = (state != ST_IDLE);

endmodule
