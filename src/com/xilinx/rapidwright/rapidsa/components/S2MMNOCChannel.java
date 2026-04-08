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
 * RapidComponent for an S2MM DMA channel via single NoC NMU.
 *
 * Accepts an 8-bit AXI-Stream input (from the drain tile chain) and writes
 * it to DDR through the NoC using the Xilinx AXI DMA IP (S2MM channel).
 *
 * Contains: AXI DMA (S2MM only), AXI DMA S2MM control wrapper,
 * AXI NoC (1 slave, 1 INI master).
 */
public class S2MMNOCChannel implements RapidComponent {

    public S2MMNOCChannel() {
    }

    @Override
    public String getComponentName() {
        return "S2MMNOCChannel";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        String bdName = "s2mm_channel";

        List<String> lines = new ArrayList<>();

        // Add HDL sources needed for module references in the block design
        lines.add("add_files " + rtlPath + "axi_dma_s2mm_ctrl_wrapper.v");
        lines.add("add_files " + rtlPath + "axi_dma_s2mm_ctrl.sv");
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

        // Control
        lines.add("create_bd_port -dir I start");
        lines.add("create_bd_port -dir I -from 31 -to 0 dst_addr");
        lines.add("create_bd_port -dir I -from 22 -to 0 transfer_length");
        lines.add("create_bd_port -dir O done");
        lines.add("create_bd_port -dir O busy");
        lines.add("create_bd_port -dir O error");

        // Input AXI-Stream (from drain tile)
        lines.add("create_bd_port -dir I -from 7 -to 0 s_data");
        lines.add("create_bd_port -dir I s_valid");
        lines.add("create_bd_port -dir O s_ready");

        // =====================================================================
        // IP / module instances
        // =====================================================================
        lines.add("create_bd_cell -type module -reference axi_dma_s2mm_ctrl_wrapper axi_dma_s2mm_ctrl_wr_0");

        lines.add("set axi_dma_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dma:7.1 axi_dma_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.c_include_mm2s {0} "
                + "CONFIG.c_include_sg {0} "
                + "CONFIG.c_include_s2mm_dre {0} "
                + "CONFIG.c_m_axi_s2mm_data_width {128} "
                + "CONFIG.c_s_axis_s2mm_tdata_width {8} "
                + "CONFIG.c_s2mm_burst_size {2}"
                + "] $axi_dma_0");

        // NoC NMU — 1 slave AXI, 1 INI master, no memory controller
        lines.add("set axi_noc_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_noc:1.1 axi_noc_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.NUM_SI {1} "
                + "CONFIG.NUM_MI {0} "
                + "CONFIG.NUM_MC {0} "
                + "CONFIG.NUM_CLKS {1} "
                + "CONFIG.NUM_NMI {1}"
                + "] $axi_noc_0");
        lines.add("set_property -dict [list CONFIG.CONNECTIONS {M00_INI {}}] [get_bd_intf_pins /axi_noc_0/S00_AXI]");

        // AXI Register Slice between DMA and NoC
        lines.add("set axi_reg_slice_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_register_slice:2.1 axi_reg_slice_0]");

        // AXI-Stream Register Slice on input stream
        lines.add("set axis_reg_slice_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axis_register_slice:1.1 axis_reg_slice_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.TDATA_NUM_BYTES {1}"
                + "] $axis_reg_slice_0");

        // =====================================================================
        // Control ports -> S2MM DMA controller
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports start] [get_bd_pins axi_dma_s2mm_ctrl_wr_0/start]");
        lines.add("connect_bd_net [get_bd_ports dst_addr] [get_bd_pins axi_dma_s2mm_ctrl_wr_0/dst_addr]");
        lines.add("connect_bd_net [get_bd_ports transfer_length] [get_bd_pins axi_dma_s2mm_ctrl_wr_0/transfer_length]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_s2mm_ctrl_wr_0/done] [get_bd_ports done]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_s2mm_ctrl_wr_0/busy] [get_bd_ports busy]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_s2mm_ctrl_wr_0/error] [get_bd_ports error]");

        // =====================================================================
        // DMA controller -> DMA IP -> Register Slice -> NoC
        // =====================================================================
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_s2mm_ctrl_wr_0/m_axi_lite] "
                + "[get_bd_intf_pins axi_dma_0/S_AXI_LITE]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_0/M_AXI_S2MM] "
                + "[get_bd_intf_pins axi_reg_slice_0/S_AXI]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_reg_slice_0/M_AXI] "
                + "[get_bd_intf_pins axi_noc_0/S00_AXI]");

        // =====================================================================
        // External stream -> AXIS Register Slice -> DMA S_AXIS_S2MM
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports s_data] [get_bd_pins axis_reg_slice_0/s_axis_tdata]");
        lines.add("connect_bd_net [get_bd_ports s_valid] [get_bd_pins axis_reg_slice_0/s_axis_tvalid]");
        lines.add("connect_bd_net [get_bd_pins axis_reg_slice_0/s_axis_tready] [get_bd_ports s_ready]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axis_reg_slice_0/M_AXIS] "
                + "[get_bd_intf_pins axi_dma_0/S_AXIS_S2MM]");

        // =====================================================================
        // Clock connections
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/s_axi_lite_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/m_axi_s2mm_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_s2mm_ctrl_wr_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_noc_0/aclk0]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_reg_slice_0/aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axis_reg_slice_0/aclk]");

        // =====================================================================
        // Reset connections
        // =====================================================================
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_0/axi_resetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_s2mm_ctrl_wr_0/rst_n]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_reg_slice_0/aresetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axis_reg_slice_0/aresetn]");

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
        lines.add("set_property synth_checkpoint_mode None $bd_file");
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
                "SLICE_X320Y820:SLICE_X343Y851 "
                + "RAMB36_X14Y206:RAMB36_X15Y213 RAMB18_X14Y412:RAMB18_X15Y427 "
                + "NOC_NMU512_X3Y17:NOC_NMU512_X3Y17 NOC_NSU512_X3Y17:NOC_NSU512_X3Y17 "
                + "NOC_NPS_VNOC_X3Y34:NOC_NPS_VNOC_X3Y35 "
                + "IRI_QUAD_X218Y3308:IRI_QUAD_X229Y3435");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        // Input stream from drain (LEFT)
        lines.add("s_data.* LEFT");
        lines.add("s_valid LEFT");
        lines.add("s_ready LEFT");
        // Control signals (TOP)
        lines.add("start TOP");
        lines.add("dst_addr.* TOP");
        lines.add("transfer_length.* TOP");
        lines.add("done TOP");
        lines.add("busy TOP");
        lines.add("error TOP");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
