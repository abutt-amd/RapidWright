/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rapidsa.components;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.design.tools.InlineFlopTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RapidComponent for the MM2S DMA channel via NoC NMU.
 *
 * Loads K-major matrices from DDR through the NoC. Outputs raw 128-bit data
 * on two ports (A for activations, B for weights) with valid signals.
 * No DTU, no tags — the K-major data layout makes distribution trivial.
 *
 * The DMA output (128-bit matched width) is demuxed to port A or B based
 * on the sequencer's active_channel signal. Each port feeds a forwarding
 * chain of Edge Buffer tiles.
 *
 * Contains: AXI DMA (MM2S, 128:128), AXI DMA controller, MM2S NOC sequencer,
 * SA FSM, AXI-Lite slave, AXI NoC (NMU + NSU), AXI register slice.
 */
public class MM2SNOCChannel implements RapidComponent {

    public MM2SNOCChannel() {
    }

    @Override
    public String getComponentName() {
        return "MM2SNOCChannel";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        String bdName = "mm2s_channel";

        List<String> lines = new ArrayList<>();

        // Add HDL sources needed for module references in the block design
        lines.add("add_files " + rtlPath + "axi_dma_mm2s_ctrl_wrapper.v");
        lines.add("add_files " + rtlPath + "axi_dma_mm2s_ctrl.sv");
        lines.add("add_files " + rtlPath + "mm2s_noc_sequencer_wrapper.v");
        lines.add("add_files " + rtlPath + "mm2s_noc_sequencer.sv");
        lines.add("add_files " + rtlPath + "sa_fsm.sv");
        lines.add("add_files " + rtlPath + "sa_fsm_wrapper.v");
        lines.add("add_files " + rtlPath + "mm2s_axilite_slave_wrapper.v");
        lines.add("add_files " + rtlPath + "mm2s_axilite_slave.sv");
        lines.add("add_files " + rtlPath + "valid_demux.v");
        lines.add("set_property source_mgmt_mode All [current_project]");
        lines.add("update_compile_order -fileset sources_1");

        // Create block design
        lines.add("create_bd_design " + bdName);

        // =====================================================================
        // External ports
        // =====================================================================
        lines.add("set clk [create_bd_port -dir I -type clk -freq_hz 100000000 clk]");
        lines.add("set_property CONFIG.ASSOCIATED_RESET {resetn} $clk");
        lines.add("set resetn [create_bd_port -dir I -type rst resetn]");
        lines.add("set_property CONFIG.POLARITY ACTIVE_LOW $resetn");

        // 128-bit data output port A (activations → input Edge Buffer chain)
        lines.add("create_bd_port -dir O -from 127 -to 0 m_data_a");
        lines.add("create_bd_port -dir O m_valid_a");

        // 128-bit data output port B (weights → weight Edge Buffer chain)
        lines.add("create_bd_port -dir O -from 127 -to 0 m_data_b");
        lines.add("create_bd_port -dir O m_valid_b");

        // SA FSM output ports
        lines.add("create_bd_port -dir O a_rd_en");
        lines.add("create_bd_port -dir O b_rd_en");
        lines.add("create_bd_port -dir O output_wr_en");
        lines.add("create_bd_port -dir O done");
        lines.add("create_bd_port -dir O sa_accum_shift");
        lines.add("create_bd_port -dir O s2mm_start");
        lines.add("create_bd_port -dir I s2mm_done");

        // Interrupt output
        lines.add("create_bd_port -dir O interrupt");

        // =====================================================================
        // IP / module instances
        // =====================================================================
        lines.add("create_bd_cell -type module -reference sa_fsm_wrapper sa_fsm_0");
        lines.add("create_bd_cell -type module -reference mm2s_noc_sequencer_wrapper mm2s_noc_sequencer_wr_0");
        lines.add("create_bd_cell -type module -reference axi_dma_mm2s_ctrl_wrapper axi_dma_mm2s_ctrl_wr_0");
        lines.add("create_bd_cell -type module -reference mm2s_axilite_slave_wrapper mm2s_axilite_slave_wr_0");
        lines.add("create_bd_cell -type module -reference valid_demux valid_demux_0");

        lines.add("set axi_dma_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dma:7.1 axi_dma_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.c_include_s2mm {0} "
                + "CONFIG.c_include_sg {0} "
                + "CONFIG.c_include_mm2s_dre {0} "
                + "CONFIG.c_m_axi_mm2s_data_width {128} "
                + "CONFIG.c_m_axis_mm2s_tdata_width {128} "
                + "CONFIG.c_mm2s_burst_size {16}"
                + "] $axi_dma_0");

        // NoC — 1 NMU (data to DDR) + 1 NSU (control from host)
        lines.add("set axi_noc_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_noc:1.1 axi_noc_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.NUM_SI {1} "
                + "CONFIG.NUM_MI {1} "
                + "CONFIG.NUM_MC {0} "
                + "CONFIG.NUM_CLKS {1} "
                + "CONFIG.NUM_NMI {1} "
                + "CONFIG.NUM_NSI {1}"
                + "] $axi_noc_0");
        lines.add("set_property -dict [list CONFIG.CONNECTIONS {M00_INI {read_bw {500} write_bw {500}}}] [get_bd_intf_pins /axi_noc_0/S00_AXI]");
        lines.add("set_property -dict [list CONFIG.CONNECTIONS {M00_AXI {read_bw {500} write_bw {500}}}] [get_bd_intf_pins /axi_noc_0/S00_INI]");

        // AXI Register Slice between DMA and NoC
        lines.add("set axi_reg_slice_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_register_slice:2.1 axi_reg_slice_0]");

        // Update module references to pick up current port lists
        lines.add("update_module_reference [get_ips]");

        // =====================================================================
        // NoC NSU -> AXI4 Slave (control path from host)
        // =====================================================================
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_noc_0/M00_AXI] "
                + "[get_bd_intf_pins mm2s_axilite_slave_wr_0/s_axi]");

        // =====================================================================
        // AXI4 Slave -> FSM start + Sequencer addresses (internal wiring)
        // =====================================================================
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/start] [get_bd_pins sa_fsm_0/start]");
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/src_addr_a] [get_bd_pins mm2s_noc_sequencer_wr_0/src_addr_a]");
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/transfer_length_a] [get_bd_pins mm2s_noc_sequencer_wr_0/transfer_length_a]");
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/src_addr_b] [get_bd_pins mm2s_noc_sequencer_wr_0/src_addr_b]");
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/transfer_length_b] [get_bd_pins mm2s_noc_sequencer_wr_0/transfer_length_b]");

        // FSM -> Sequencer start, Sequencer done -> FSM
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/mm2s_start] [get_bd_pins mm2s_noc_sequencer_wr_0/start]");
        lines.add("connect_bd_net [get_bd_pins mm2s_noc_sequencer_wr_0/done] [get_bd_pins sa_fsm_0/mm2s_done]");

        // FSM done + busy -> AXI4 Slave status + external ports
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/done] [get_bd_pins mm2s_axilite_slave_wr_0/done] [get_bd_ports done]");
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/busy] [get_bd_pins mm2s_axilite_slave_wr_0/busy]");

        // Interrupt output
        lines.add("connect_bd_net [get_bd_pins mm2s_axilite_slave_wr_0/interrupt] [get_bd_ports interrupt]");

        // =====================================================================
        // Sequencer dma_* ↔ DMA controller
        // =====================================================================
        lines.add("connect_bd_net [get_bd_pins mm2s_noc_sequencer_wr_0/dma_start] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/start]");
        lines.add("connect_bd_net [get_bd_pins mm2s_noc_sequencer_wr_0/dma_src_addr] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/src_addr]");
        lines.add("connect_bd_net [get_bd_pins mm2s_noc_sequencer_wr_0/dma_transfer_length] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/transfer_length]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/done] [get_bd_pins mm2s_noc_sequencer_wr_0/dma_done]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/busy] [get_bd_pins mm2s_noc_sequencer_wr_0/dma_busy]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/error] [get_bd_pins mm2s_noc_sequencer_wr_0/dma_error]");

        // =====================================================================
        // DMA controller ↔ DMA IP ↔ Register Slice ↔ NoC
        // =====================================================================
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_mm2s_ctrl_wr_0/m_axi_lite] "
                + "[get_bd_intf_pins axi_dma_0/S_AXI_LITE]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_0/M_AXI_MM2S] "
                + "[get_bd_intf_pins axi_reg_slice_0/S_AXI]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_reg_slice_0/M_AXI] "
                + "[get_bd_intf_pins axi_noc_0/S00_AXI]");

        // =====================================================================
        // DMA M_AXIS_MM2S (128-bit) → demux to port A / port B
        // Data is broadcast to both ports. Valid is gated by active_channel.
        // active_channel=0 → activations (port A), active_channel=1 → weights (port B)
        // =====================================================================
        lines.add("connect_bd_net [get_bd_pins axi_dma_0/m_axis_mm2s_tdata] [get_bd_ports m_data_a] [get_bd_ports m_data_b]");

        // Valid demux: m_valid_a = tvalid & ~active_channel,  m_valid_b = tvalid & active_channel
        lines.add("connect_bd_net [get_bd_pins axi_dma_0/m_axis_mm2s_tvalid] [get_bd_pins valid_demux_0/tvalid]");
        lines.add("connect_bd_net [get_bd_pins mm2s_noc_sequencer_wr_0/active_channel] [get_bd_pins valid_demux_0/active_channel]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins valid_demux_0/resetn]");
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/m_valid_a] [get_bd_ports m_valid_a]");
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/m_valid_b] [get_bd_ports m_valid_b]");

        // tready always high — no backpressure, FIFOs sized to never overflow
        lines.add("set tready_const [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 tready_const]");
        lines.add("set_property -dict [list CONFIG.CONST_WIDTH {1} CONFIG.CONST_VAL {1}] $tready_const");
        lines.add("connect_bd_net [get_bd_pins tready_const/dout] [get_bd_pins axi_dma_0/m_axis_mm2s_tready]");

        // =====================================================================
        // SA FSM connections
        // =====================================================================
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/reset] [get_bd_pins sa_fsm_0/reset]");

        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/a_rd_en] [get_bd_pins valid_demux_0/a_rd_en_in]");
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/b_rd_en] [get_bd_pins valid_demux_0/b_rd_en_in]");
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/output_wr_en] [get_bd_pins valid_demux_0/output_wr_en_in]");
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/a_rd_en] [get_bd_ports a_rd_en]");
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/b_rd_en] [get_bd_ports b_rd_en]");
        lines.add("connect_bd_net [get_bd_pins valid_demux_0/output_wr_en] [get_bd_ports output_wr_en]");
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/sa_accum_shift] [get_bd_ports sa_accum_shift]");
        lines.add("connect_bd_net [get_bd_pins sa_fsm_0/s2mm_start] [get_bd_ports s2mm_start]");
        lines.add("connect_bd_net [get_bd_ports s2mm_done] [get_bd_pins sa_fsm_0/s2mm_done]");

        // =====================================================================
        // Clock connections
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins sa_fsm_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/s_axi_lite_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/m_axi_mm2s_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_noc_0/aclk0]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins mm2s_noc_sequencer_wr_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_reg_slice_0/aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins mm2s_axilite_slave_wr_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins valid_demux_0/clk]");

        // =====================================================================
        // Reset connections
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_0/axi_resetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/rst_n]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins mm2s_noc_sequencer_wr_0/rst_n]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_reg_slice_0/aresetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins mm2s_axilite_slave_wr_0/rst_n]");

        // =====================================================================
        // Address assignment
        // =====================================================================
        lines.add("assign_bd_address");

        // =====================================================================
        // Validate, save, generate wrapper, set as top
        // =====================================================================
        lines.add("validate_bd_design");
        lines.add("save_bd_design");
        lines.add("set bd_file [get_files " + bdName + ".bd]");
        lines.add("generate_target all $bd_file");
        lines.add("make_wrapper -files $bd_file -top");
        lines.add("set wrapper_file [file normalize [file dirname $bd_file]/hdl/" + bdName + "_wrapper.v]");
        lines.add("add_files -norecurse $wrapper_file");
        lines.add("set_property top " + bdName + "_wrapper [current_fileset]");

        return lines;
    }

    @Override
    public String getClkName() {
        return "clk";
    }

    @Override
    public String getResetName() {
        return "resetn";
    }

    @Override
    public PBlock getPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        return new PBlock(device,
                "SLICE_X322Y868:SLICE_X343Y899 "
                + "RAMB36_X14Y218:RAMB36_X15Y225 RAMB18_X14Y436:RAMB18_X15Y451 "
                + "NOC_NMU512_X3Y18:NOC_NMU512_X3Y18 NOC_NSU512_X3Y18:NOC_NSU512_X3Y18 "
                + "NOC_NPS_VNOC_X3Y36:NOC_NPS_VNOC_X3Y37 "
                + "IRI_QUAD_X218Y3500:IRI_QUAD_X229Y3627");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        // Port A — activations → input Edge Buffers (LEFT)
        lines.add("m_data_a.* LEFT");
        // Port B — weights → weight Edge Buffers (BOTTOM)
        lines.add("m_data_b.* BOTTOM");
        // m_valid_a / m_valid_b are registered inside valid_demux_0, so we
        // don't add an InlineFlopTools register on top — that would
        // double-register them and misalign with the data inline flops.
        // FSM outputs (BOTTOM).
        // a_rd_en / b_rd_en / output_wr_en are registered inside valid_demux_0
        // so we don't add an InlineFlopTools register on top of them.
        lines.add("sa_accum_shift BOTTOM");
        lines.add("done BOTTOM");
        lines.add("s2mm_start BOTTOM");
        lines.add("s2mm_done BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
