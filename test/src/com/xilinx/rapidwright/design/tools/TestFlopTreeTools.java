/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BELPin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestFlopTreeTools {

    private static SiteInst createVersalSliceWithPlacedFF(Design design) {
        Cell cell = design.createAndPlaceCell("existing_ff", Unisim.FDRE, "SLICE_X40Y0/AFF");
        return cell.getSiteInst();
    }

    @Test
    public void testAllowVersalSliceWithHiddenGndReset() {
        Design design = new Design("testAllowVersalSliceWithHiddenGndReset", "xcvc1902");
        SiteInst siteInst = createVersalSliceWithPlacedFF(design);
        BELPin sr = siteInst.getBEL("AFF").getPin("SR");

        Assertions.assertNull(siteInst.getSitePinInst("RST"));
        Assertions.assertTrue(siteInst.routeIntraSiteNet(design.getGndNet(), sr, sr));
        Assertions.assertNull(siteInst.getSitePinInst("RST"));
        Assertions.assertTrue(siteInst.getNetFromSiteWire("RST").isGNDNet());
        Assertions.assertTrue(FlopTreeTools.isControlSetCompatibleForInsertedFDRE(siteInst, siteInst.getBEL("BFF")));
    }

    @Test
    public void testRejectVersalSliceWithHiddenVccReset() {
        Design design = new Design("testRejectVersalSliceWithHiddenVccReset", "xcvc1902");
        SiteInst siteInst = createVersalSliceWithPlacedFF(design);
        BELPin sr = siteInst.getBEL("AFF").getPin("SR");

        Assertions.assertNull(siteInst.getSitePinInst("RST"));
        Assertions.assertTrue(siteInst.routeIntraSiteNet(design.getVccNet(), sr, sr));
        Assertions.assertNull(siteInst.getSitePinInst("RST"));
        Assertions.assertTrue(siteInst.getNetFromSiteWire("RST").isVCCNet());
        Assertions.assertFalse(FlopTreeTools.isControlSetCompatibleForInsertedFDRE(siteInst, siteInst.getBEL("BFF")));
    }

    @Test
    public void testRejectVersalSliceWithHiddenNonVccClockEnable() {
        Design design = new Design("testRejectVersalSliceWithHiddenNonVccClockEnable", "xcvc1902");
        SiteInst siteInst = createVersalSliceWithPlacedFF(design);
        BELPin ce = siteInst.getBEL("AFF").getPin("CE");
        Net ceNet = design.createNet("ce_net");

        Assertions.assertNull(siteInst.getSitePinInst("CKEN1"));
        Assertions.assertTrue(siteInst.routeIntraSiteNet(ceNet, ce, ce));
        Assertions.assertNull(siteInst.getSitePinInst("CKEN1"));
        Assertions.assertEquals(ceNet, siteInst.getNetFromSiteWire("CKEN1"));
        Assertions.assertFalse(FlopTreeTools.isControlSetCompatibleForInsertedFDRE(siteInst, siteInst.getBEL("BFF2")));
    }
}
