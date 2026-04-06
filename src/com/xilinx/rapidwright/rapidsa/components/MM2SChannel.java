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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.design.tools.InlineFlopTools;
import com.xilinx.rapidwright.design.tools.RegisterInitTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RapidComponent for a complete MM2S DMA channel with data tagging.
 *
 * Contains: AXI DMA (MM2S only), AXI BRAM Controller, Embedded Memory Generator,
 * address slice, AXI DMA control wrapper, and data tag unit.
 *
 * The data tag unit has dont_touch registers for tag_source_sel, matrix_height,
 * and matrix_width that can be modified post-compilation via RegisterInitTools.
 */
public class MM2SChannel implements RapidComponent {

    private final int tagSource;
    private final String memInitFile;

    private static final int DIM_WIDTH = 8;
    private static final String TAG_SOURCE_REG = "mm2s_channel_i/data_tag_unit_a_0/inst/inst/tag_source_sel_reg";
    private static final String HEIGHT_REG = "mm2s_channel_i/data_tag_unit_a_0/inst/inst/matrix_height_reg_reg";
    private static final String WIDTH_REG = "mm2s_channel_i/data_tag_unit_a_0/inst/inst/matrix_width_reg_reg";

    /**
     * @param tagSource 0 for ROW tagging, 1 for COL tagging
     * @param memInitFile Absolute path to .mem init file for the embedded memory
     */
    public MM2SChannel(int tagSource, String memInitFile) {
        this.tagSource = tagSource;
        this.memInitFile = memInitFile;
    }

    public static void setTagSource(Design design, String instPrefix, int value) {
        String cellName = instPrefix + "/" + TAG_SOURCE_REG;
        Cell cell = design.getCell(cellName);
        if (cell == null) {
            throw new RuntimeException("Cell not found: " + cellName);
        }
        String initString = (value != 0) ? "1'b1" : "1'b0";
        cell.getEDIFCellInst().addProperty("INIT", initString);
    }

    public static void setMatrixHeight(Design design, String instPrefix, int value) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/" + HEIGHT_REG, value, DIM_WIDTH);
    }

    public static void setMatrixWidth(Design design, String instPrefix, int value) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/" + WIDTH_REG, value, DIM_WIDTH);
    }

    @Override
    public String getComponentName() {
        return "MM2SChannel";
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
        lines.add("add_files " + rtlPath + "data_tag_unit_wrapper.v");
        lines.add("add_files " + rtlPath + "data_tag_unit.sv");
        lines.add("set_property source_mgmt_mode All [current_project]");

        // Create block design
        lines.add("create_bd_design " + bdName);

        // External ports
        lines.add("set clk [create_bd_port -dir I -type clk -freq_hz 100000000 clk]");
        lines.add("set_property CONFIG.ASSOCIATED_RESET {resetn} $clk");
        lines.add("set resetn [create_bd_port -dir I -type rst resetn]");
        lines.add("set_property CONFIG.POLARITY ACTIVE_LOW $resetn");
        lines.add("create_bd_port -dir I start_mm2s_a");
        lines.add("create_bd_port -dir I -from 31 -to 0 src_addr_mm2s_a");
        lines.add("create_bd_port -dir I -from 22 -to 0 transfer_length_mm2s_a");
        lines.add("create_bd_port -dir O done_mm2s_a");
        lines.add("create_bd_port -dir O busy_mm2s_a");
        lines.add("create_bd_port -dir O error_mm2s_a");

        // IP instances
        lines.add("create_bd_cell -type module -reference axi_dma_mm2s_ctrl_wrapper axi_dma_mm2s_ctrl_wr_0");

        lines.add("set axi_dma_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dma:7.1 axi_dma_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.c_include_s2mm {0} "
                + "CONFIG.c_include_sg {0} "
                + "CONFIG.c_include_mm2s_dre {0} "
                + "CONFIG.c_m_axi_mm2s_data_width {128} "
                + "CONFIG.c_m_axis_mm2s_tdata_width {8} "
                + "CONFIG.c_mm2s_burst_size {2}"
                + "] $axi_dma_0");

        lines.add("set axi_bram_ctrl_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_bram_ctrl:4.1 axi_bram_ctrl_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.DATA_WIDTH {128} "
                + "CONFIG.SINGLE_PORT_BRAM {1}"
                + "] $axi_bram_ctrl_0");

        lines.add("set ilslice_0 [create_bd_cell -type inline_hdl -vlnv xilinx.com:inline_hdl:ilslice:1.0 ilslice_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.DIN_WIDTH {13} "
                + "CONFIG.DIN_FROM {5} "
                + "CONFIG.DIN_TO {4}"
                + "] $ilslice_0");

        lines.add("create_bd_cell -type module -reference data_tag_unit_wrapper data_tag_unit_a_0");

        lines.add("set emb_mem_gen_0 [create_bd_cell -type ip -vlnv xilinx.com:ip:emb_mem_gen:1.0 emb_mem_gen_0]");
        lines.add("set_property -dict [list "
                + "CONFIG.MEMORY_TYPE {Single_Port_ROM} "
                + "CONFIG.MEMORY_DEPTH {4} "
                + "CONFIG.WRITE_DATA_WIDTH_A {128} "
                + "CONFIG.READ_LATENCY_A {1} "
                + "CONFIG.USE_MEMORY_BLOCK {Stand_Alone} "
                + "CONFIG.MEMORY_INIT_FILE {" + memInitFile + "}"
                + "] $emb_mem_gen_0");

        // Interface connections
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_mm2s_ctrl_wr_0/m_axi_lite] "
                + "[get_bd_intf_pins axi_dma_0/S_AXI_LITE]");
        lines.add("connect_bd_intf_net [get_bd_intf_pins axi_dma_0/M_AXI_MM2S] "
                + "[get_bd_intf_pins axi_bram_ctrl_0/S_AXI]");

        // DMA stream -> data_tag_unit
        lines.add("connect_bd_net [get_bd_pins axi_dma_0/m_axis_mm2s_tdata] [get_bd_pins data_tag_unit_a_0/s_axis_tdata]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_0/m_axis_mm2s_tvalid] [get_bd_pins data_tag_unit_a_0/s_axis_tvalid]");
        lines.add("connect_bd_net [get_bd_pins data_tag_unit_a_0/s_axis_tready] [get_bd_pins axi_dma_0/m_axis_mm2s_tready]");

        // Data tag unit outputs
        lines.add("create_bd_port -dir O -from 7 -to 0 m_data_a");
        lines.add("create_bd_port -dir O -from 7 -to 0 m_tag_a");
        lines.add("create_bd_port -dir O m_valid_a");
        lines.add("create_bd_port -dir I m_ready_a");
        lines.add("connect_bd_net [get_bd_pins data_tag_unit_a_0/m_data] [get_bd_ports m_data_a]");
        lines.add("connect_bd_net [get_bd_pins data_tag_unit_a_0/m_tag] [get_bd_ports m_tag_a]");
        lines.add("connect_bd_net [get_bd_pins data_tag_unit_a_0/m_valid] [get_bd_ports m_valid_a]");
        lines.add("connect_bd_net [get_bd_ports m_ready_a] [get_bd_pins data_tag_unit_a_0/m_ready]");

        // BRAM wiring
        lines.add("connect_bd_net [get_bd_pins axi_bram_ctrl_0/bram_addr_a] [get_bd_pins ilslice_0/Din]");
        lines.add("connect_bd_net [get_bd_pins ilslice_0/Dout] [get_bd_pins emb_mem_gen_0/addra]");
        lines.add("connect_bd_net [get_bd_pins axi_bram_ctrl_0/bram_clk_a] [get_bd_pins emb_mem_gen_0/clka]");
        lines.add("connect_bd_net [get_bd_pins axi_bram_ctrl_0/bram_en_a] [get_bd_pins emb_mem_gen_0/ena]");
        lines.add("connect_bd_net [get_bd_pins axi_bram_ctrl_0/bram_rst_a] [get_bd_pins emb_mem_gen_0/rsta]");
        lines.add("connect_bd_net [get_bd_pins emb_mem_gen_0/douta] [get_bd_pins axi_bram_ctrl_0/bram_rddata_a]");

        // Control signals
        lines.add("connect_bd_net [get_bd_ports start_mm2s_a] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/start]");
        lines.add("connect_bd_net [get_bd_ports src_addr_mm2s_a] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/src_addr]");
        lines.add("connect_bd_net [get_bd_ports transfer_length_mm2s_a] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/transfer_length]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/done] [get_bd_ports done_mm2s_a]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/busy] [get_bd_ports busy_mm2s_a]");
        lines.add("connect_bd_net [get_bd_pins axi_dma_mm2s_ctrl_wr_0/error] [get_bd_ports error_mm2s_a]");

        // Clock connections
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/s_axi_lite_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_0/m_axi_mm2s_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_bram_ctrl_0/s_axi_aclk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/clk]");
        lines.add("connect_bd_net [get_bd_ports clk] [get_bd_pins data_tag_unit_a_0/clk]");

        // Reset connections
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_0/axi_resetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_bram_ctrl_0/s_axi_aresetn]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins axi_dma_mm2s_ctrl_wr_0/rst_n]");
        lines.add("connect_bd_net [get_bd_ports resetn] [get_bd_pins data_tag_unit_a_0/rst_n]");

        // Address mapping
        lines.add("assign_bd_address [get_bd_addr_segs axi_dma_0/S_AXI_LITE/Reg] "
                + "-target_address_space [get_bd_addr_spaces axi_dma_mm2s_ctrl_wr_0/m_axi_lite] "
                + "-offset 0x00000000 -range 64K");
        lines.add("assign_bd_address [get_bd_addr_segs axi_bram_ctrl_0/S_AXI/Mem0] "
                + "-target_address_space [get_bd_addr_spaces axi_dma_0/Data_MM2S] "
                + "-offset 0x00000000 -range 4K");

        // Validate, save, generate wrapper, set as top
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
                "SLICE_X332Y860:SLICE_X351Y879 "
                + "RAMB36_X14Y216:RAMB36_X15Y220 RAMB18_X14Y432:RAMB18_X15Y441 "
                + "IRI_QUAD_X228Y3468:IRI_QUAD_X229Y3547");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("m_data_a.* LEFT");
        lines.add("m_tag_a.* LEFT");
        lines.add("m_valid_a LEFT");
        lines.add("m_ready_a LEFT");
        lines.add("start_mm2s_a TOP");
        lines.add("src_addr_mm2s_a.* TOP");
        lines.add("transfer_length_mm2s_a.* TOP");
        lines.add("done_mm2s_a TOP");
        lines.add("busy_mm2s_a TOP");
        lines.add("error_mm2s_a TOP");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
