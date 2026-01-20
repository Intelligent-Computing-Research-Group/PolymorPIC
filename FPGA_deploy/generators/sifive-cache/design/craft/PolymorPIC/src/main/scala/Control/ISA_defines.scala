// Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
// PolymorPIC is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          http://license.coscl.org.cn/MulanPSL2
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
// See the Mulan PSL v2 for more details.

package PIC

import chisel3._
import chisel3.util._

case class ISA_LOAD(
    override val name:String="LOAD",
    override val describe:String="Load a mat from DRAM to on-chip.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=true,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="memSrcAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of src.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="onChipDstAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="The on-chip addr of dst.")

    // Size Info
    addISASetRegInfo(setRegType="size",regSubField="row",
                    // ISA field info
                    name="matRow",
                    len=sysCfg._P_nRow_sigLen,
                    annotation="#rows in matrix.")
    addISASetRegInfo(setRegType="size",regSubField="bytePerRow",
                // ISA field info
                name="mat_Col",
                len=sysCfg._P_bytePerRow_sigLen,
                annotation="The number bytes each row in matrix.")
    addISASetRegInfo(setRegType="size",regSubField="offset",
                // ISA field info
                name="rowOffset",
                len=sysCfg.offset_signLen,
                annotation="The number bytes between row[i][0] and row[i+1][0].")
    
    // Paramters
    addModuleID_fixVal(FuncModule.LOAD_ID)

    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")
}


case class ISA_P2SL(
    override val name:String="P2SL",
    override val describe:String="Load left matrix bit-slice from DRAM to on-chip.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=true,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="memSrcAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of src.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="onChipDstAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="he on-chip addr of dst.")

    // Size Info
    addISASetRegInfo(setRegType="size",regSubField="row",
                    // ISA field info
                    name="matL_Row",
                    len=sysCfg._L_nRow_sigLen,
                    annotation="#rows in left matrix.")
    addISASetRegInfo(setRegType="size",regSubField="offset",
                // ISA field info
                name="rowOffset",
                len=sysCfg.offset_signLen,
                annotation="The number bytes between row[i][0] and row[i+1][0].")
    
    // Paramters
    addModuleID_fixVal(FuncModule.P2SL_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="bitWidth",
                len=log2Ceil(8),
                annotation="The valid bit of elements in L. Needs to -1!")

    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")
}

case class ISA_P2SR(
    override val name:String="P2SR",
    override val describe:String="Load right matrix bit-slice from DRAM to on-chip.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=true,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="memSrcAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of src.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="onChipDstAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="he on-chip addr of dst.")

    // Size Info
    addISASetRegInfo(setRegType="size",regSubField="row",
                    // ISA field info
                    name="matR_Row",
                    len=math.max(sysCfg._R_nRow_sigLen,sysCfg._RT_nRow_sigLen),
                    annotation="Number of row in right matrix.",
                    limitation=(1,sysCfg.core_config.bitlineNums))
    addISASetRegInfo(setRegType="size",regSubField="bytePerRow",
                // ISA field info
                name="matR_Col",
                len=math.max(sysCfg._R_bytePerRow_sigLen,sysCfg._RT_bytePerRow_sigLen),
                annotation="Number of col in right matrix.",
                limitation=(1,sysCfg.core_config.wordlineNums))
    addISASetRegInfo(setRegType="size",regSubField="offset",
                // ISA field info
                name="rowOffset",
                len=sysCfg.offset_signLen,
                annotation="The number bytes between row[i][0] and row[i+1][0].")
                

    // Paramters
    addModuleID_fixVal(FuncModule.P2SR_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="nBufPerMat",
                len=log2Ceil(4),
                annotation="Use how many buf in each mat.",
                limitation=(1,3))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="bitWidth",
                len=log2Ceil(8),
                annotation="The valid bit of elements in R. Needs to -1!")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="if_T",
                len=1,
                annotation="If transpos.")
    // cmd id
    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")
}

case class ISA_IM2COL(
    override val name:String="IM2COL",
    override val describe:String="IM2COL from DRAM to on-chip then to DRAM.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=false,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="memSrcAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of src.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="memDstAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of dst.")

    // Paramters
    addModuleID_fixVal(FuncModule.IM2COL_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="featrueSize",
                len=sysCfg.inferWidth(sysCfg.max_featrueSize),
                annotation="The height/width of feature map before padding.",
                limitation=(1,sysCfg.max_featrueSize))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="kernalSize",
                len=sysCfg.inferWidth(sysCfg.max_kernalSize),
                annotation="The kernal size.",
                limitation=(1,sysCfg.max_kernalSize))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="strideSize",
                len=sysCfg.inferWidth(sysCfg.max_stride),
                annotation="The stride size.",
                limitation=(1,sysCfg.max_stride))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="nPad",
                len=sysCfg.inferWidth(sysCfg.max_padSize),
                annotation="The pad size.",
                limitation=(1,sysCfg.max_padSize))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="toCol",
                len=1,
                annotation="Whether write back to dram, or make padding and just keep it on bank.")
}

case class ISA_ACC(
    override val name:String="ACC",
    override val describe:String="ACC from on-chip to on-chip.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=false,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="onChipSrcAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="The on-chip addr of dst.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="onChipDstAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="The on-chip addr of dst.")

    // Paramters
    addModuleID_fixVal(FuncModule.ACC_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="srcNum",
                len=sysCfg.inferWidth(sysCfg.numAccRes),
                annotation="Add how many partial sum. The offset between src is 4 array.",
                limitation=(2,sysCfg.numAccRes))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="accRowNum",
                len=sysCfg.inferWidth(sysCfg.acc_src_maxRowNum),
                annotation="The number of row(64b cyrrently) to acc.",
                limitation=(1,sysCfg.core_config.wordlineNums))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="bitWidth",
                len=log2Ceil(8),
                annotation="1bit. The bitWdth of elem. 1->32b,0->16b")

    // cmd id
    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")
}

case class ISA_EXE(
    override val name:String="EXE",
    override val describe:String="Start calculation.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=false,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="baseLAddr",
                    len=sysCfg.accessCacheFullAddrLen,
                    annotation="The base address of L matrix.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="targetMatID",
                    len=log2Ceil(sysCfg.totalMatNum),
                    annotation="The target matID.")

    // Paramters
    addModuleID_fixVal(FuncModule.EXE_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="R_Valid_nRols",
                len=sysCfg.inferWidth(sysCfg.core_config.wordlineNums),
                annotation="How many R rols.(In the array, it is the num of valid row of R).",
                limitation=(1,sysCfg.core_config.wordlineNums))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="nBufPerMat",
                len=log2Ceil(4),
                annotation="How many arrays used as buffer to keep P's partial sum.",
                limitation=(1,3))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="nCalPerMat",
                len=log2Ceil(4),
                annotation="How many arrays used to conduct MAC.(The left array except used as buffers will be MAC mode.However, not all of them must work.",
                limitation=(1,3))
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="Base_R_Bit",
                len=log2Ceil(8),
                annotation="The first R bit slice's bit position. The left R bit slices are +1 one by one based on it.")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="L_Precision",
                len=log2Ceil(8),
                annotation="What is L bitWidth. It should -1. e.g., if it is 8b, write 7 here.")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="L_Block_Row",
                len=sysCfg._L_nRow_sigLen,
                annotation="Number of row in left matrix block.")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="SignL",
                len=1,
                annotation="If L is signed..")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="SignR_bitLast",
                len=1,
                annotation="If R is signed and the highest bit is in this exe.")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="accWidth",
                len=1,
                annotation=s"The accWidth. 32b->${CalInfo.ACC_32BIT_define_val};16b->${CalInfo.ACC_16BIT_define_val}")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="needL",
                len=1,
                annotation="This exe need to a new loaded L to begin, meanging the L it needs is not in array.")

    // cmd id
    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")

}

case class ISA_STORE(
    override val name:String="STORE",
    override val describe:String="Store a mat from on-chip to DRAM.",
    override val enSetSrc:Boolean=true,
    override val enSetDst:Boolean=true,
    override val enSetSize:Boolean=true,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Src addr
    addISASetRegInfo(setRegType="srcAddr",
                    // ISA field info
                    name="onChipSrcAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The on-chip addr of src.")

    // Dst addr
    addISASetRegInfo(setRegType="dstAddr",
                    // ISA field info
                    name="memDstAddr",
                    len=sysCfg.virtualAddrLen,
                    annotation="The dram addr of dst.")

    // Size Info
    addISASetRegInfo(setRegType="size",regSubField="row",
                    // ISA field info
                    name="matRow",
                    len=sysCfg._P_nRow_sigLen,
                    annotation="Number of row in matrix.Need to be reduced by one!")
    addISASetRegInfo(setRegType="size",regSubField="bytePerRow",
                // ISA field info
                name="matCol",
                len=sysCfg._P_bytePerRow_sigLen,
                annotation="The number bytes each row in matrix.")
    addISASetRegInfo(setRegType="size",regSubField="offset",
                // ISA field info
                name="rowOffset",
                len=sysCfg.offset_signLen,
                annotation="The number bytes between row[i][0] and row[i+1][0].")
    
    // Paramters
    addModuleID_fixVal(FuncModule.STORE_ID)

    // cmd id
    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")
}

case class ISA_SWITCH(
    override val name:String="SWITCH",
    override val describe:String="Mode switch.",
    override val enSetSrc:Boolean=false,
    override val enSetDst:Boolean=false,
    override val enSetSize:Boolean=false,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Paramters
    addModuleID_fixVal(FuncModule.SWITCH_ID)
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="opType",
                len=1,
                annotation=s"${PIC_Switch.ALLOC} is ALLOC, ${PIC_Switch.FREE} is FREE.")
    addISASetRegInfo(setRegType="params",regSubField="others",
                // ISA field info
                name="nLevels",
                len=sysCfg.inferWidth(sysCfg.total_levels),
                annotation=s"How many clusters of mats to Alloc. Total ${sysCfg.pic_avail_levels} is available.")


    def genSwitchHelperFunc(header_def_str:StringBuilder,header_impl_str:StringBuilder):StringBuilder=
    {
        val queryInfo=ISA_QUERY(sysCfg=sysCfg)
        val rdInfo=queryInfo.returnRdInfo
        val switchSuccessField=rdInfo.getField("switchSuccess")
        val beginMatField=rdInfo.getField("beginPicMatID")
        val endMatField=rdInfo.getField("endPicMatID")
        header_impl_str++=s"// end_matID;begin_matID;whether the switch op is success(valid op);whether the command finish;\n"
        header_def_str ++=  s"bool ifSwitchSuccess(uint64_t query_res);\n"
        header_impl_str++=s"bool ifSwitchSuccess(uint64_t query_res)\n"
        header_impl_str++=s"{	return ((query_res>>${switchSuccessField.begin})&${genMask(switchSuccessField.len)})==1;	}\n"

        header_impl_str++=s"\n"

        header_impl_str++=s"uint32_t getBeginPicMatID(uint64_t query_res)\n"
        header_impl_str++=s"{	return ((query_res>>${beginMatField.begin})&${genMask(beginMatField.len)});	}\n"

        header_impl_str++=s"\n"

        header_impl_str++=s"uint32_t getEndPicMatID(uint64_t query_res)\n"
        header_impl_str++=s"{	return ((query_res>>${endMatField.begin})&${genMask(endMatField.len)});	}\n"
        header_impl_str++=s""
    }

    def genShowSwitchInfo(header_def_str:StringBuilder,header_impl_str:StringBuilder):StringBuilder=
    {
        header_def_str ++= "void showSwitchInfo(uint64_t query_res);\n"
        header_impl_str ++= s"""
#define EN_PRINT true
void showSwitchInfo(uint64_t query_res)
{
    bool switchRes=ifSwitchSuccess(query_res);
    uint8_t beginMatID=getBeginPicMatID(query_res);
    uint8_t endMatID=getEndPicMatID(query_res);
    uint8_t availableMat=(endMatID-beginMatID)==0?0:endMatID-beginMatID+1;
    uint16_t availablePIC_KB=availableMat*MAT_SIZE_KB;
    uint16_t availableCache_KB=CACHE_SIZE_KB-availablePIC_KB;

    if(switchRes)
    {   printf("Switch successfully!\\n");  }
    else
    {   printf("Switch FAILED, operation was not executed\\n");}

    printf("################ Runtime State ###############\\n");
    #ifdef LINUX
        float percentage_PIC = (float)availablePIC_KB/(float)CACHE_SIZE_KB;
        printf("Totally %f%% Cache Memory is used as Computation\\n",percentage_PIC*100);
    #endif
    // printFloatBinary(percentage_PIC);
    printf("Available Cache Volumn (KB) = %d\\n",availableCache_KB);
    printf("Available PIC Volumn (KB) = %d\\n",availablePIC_KB);
    printf("Available Mats = %d\\n",availableMat);
    printf("Available Mats Range = %d~%d\\n",beginMatID,endMatID);
    printf("##############################################\\n");
}
"""
    }

    def genAllocFunc(header_def_str:StringBuilder,header_impl_str:StringBuilder):StringBuilder=
    {
        header_def_str ++= "void conduct_alloc(uint8_t nLevels);\n"
        header_impl_str ++= s"""
void conduct_alloc(uint8_t nLevels)
{
    ${name}(ALLOC,nLevels);
    waitImmedFree();
    uint64_t query_res=QUERY(${sysCfg.immed_cmdID},true);
    #ifdef EN_PRINT
        showSwitchInfo(query_res);
    #endif
} 
"""    
    }

    def genFreeFunc(header_def_str:StringBuilder,header_impl_str:StringBuilder):StringBuilder=
    {
        header_def_str ++= "void conduct_free(uint8_t nLevels);\n"
        header_impl_str ++= s"""
void conduct_free(uint8_t nLevels)
{
    ${name}(FREE,nLevels);
    waitImmedFree();
    uint64_t query_res=QUERY(${sysCfg.immed_cmdID},true);
    #ifdef EN_PRINT
        showSwitchInfo(query_res);
    #endif
}
"""
    }

    override def genFontEndISA(header_def_str:StringBuilder,header_impl_str:StringBuilder):Unit=
    {
        genFontEndISADef(header_def_str)
        genFontEndISAImpl(header_impl_str)
        genSwitchHelperFunc(header_def_str=header_def_str,header_impl_str=header_impl_str)
        genShowSwitchInfo(header_def_str=header_def_str,header_impl_str=header_impl_str)
        genAllocFunc(header_def_str=header_def_str,header_impl_str=header_impl_str)
        genFreeFunc(header_def_str=header_def_str,header_impl_str=header_impl_str)
    }

}

case class ISA_QUERY(
    override val name:String="QUERY",
    override val describe:String="Query execution and state.",
    override val enSetSrc:Boolean=false,
    override val enSetDst:Boolean=false,
    override val enSetSize:Boolean=false,
    override val enSetParams:Boolean=true,
    override val return_rd:Boolean=true,
    override val sysCfg:Sys_Config) extends ISA_Info_new
{
    // Paramters
    addModuleID_fixVal(FuncModule.QUERY_ID)

    addISASetRegInfo(setRegType="params",regSubField="others",
            // ISA field info
            name="is_immed",
            len=1,
            annotation="Whether the queried command is immed type command.")

    // cmd id
    addISASetRegInfo(setRegType="params",regSubField="cmdID",
                // ISA field info
                name="cmdID",
                len=sysCfg.cmdID_sigLen,
                annotation="cmdID")


    // rd
    // This part should correspond to the definition of the 'd' channel returned in CMD_resolver.
    // 
    val returnRdInfo=ParamField(
        name="queryRes",
        begin=0,
        len=64,
        annotation="Sche state, switch infomation."
    )
    // Put from LSB
    returnRdInfo.addField(
                len=1,
                name="cmdFinish",
                annotation="If the queried cmdID finished? Note: If the cmdID has been quried, you should not query it again."
                )
    returnRdInfo.addField(
                len=1,
                name="scheBusy",
                annotation="If schedular has task or mudole running."
                )
    returnRdInfo.addField(
                len=1,
                name="switchSuccess",
                annotation="If the last switch operation is successfully."
                )
    returnRdInfo.addField(
                len=log2Ceil(sysCfg.totalMatNum),
                name="beginPicMatID",
                annotation="The first Mat under PIC mode."
                )
    returnRdInfo.addField(
                len=log2Ceil(sysCfg.totalMatNum),
                name="endPicMatID",
                annotation="The last Mat under PIC mode."
                )
    

    // sche free function
    def genIfScheFreeFunc(header_def_str:StringBuilder,header_impl_str:StringBuilder):StringBuilder=
    {
        val overall_statePos=returnRdInfo.getField("scheBusy").begin
        val overall_stateLen=returnRdInfo.getField("scheBusy").len

        val cmd_statePos=returnRdInfo.getField("cmdFinish").begin
        val cmd_stateLen=returnRdInfo.getField("cmdFinish").len
    

        header_def_str ++= "void waitImmedFree();\n"

        header_impl_str ++= s"""
void waitImmedFree()
{
    while(((QUERY(${sysCfg.immed_cmdID},true)${if(overall_statePos>0) ">>"+overall_statePos.toString else ""})&${genMask(overall_stateLen)})==${genMask(overall_stateLen)})
    {}
}
"""

        val cmdID_c_type=sysCfg.get_C_Type(sysCfg.inferWidth(sysCfg.cmdID_range))
        header_def_str ++= s"void waitEnqCmdFree(${cmdID_c_type} cmdID);\n"
        header_impl_str ++= s"""
void waitEnqCmdFree(${cmdID_c_type} cmdID) // The valid cmdID range is [0,${sysCfg.cmdID_range}) !!! ${sysCfg.cmdID_range} is for immed cmd.
{
    // These command needs two query; The first for query table; The second get the result.
    QUERY(cmdID,false);
    while(((QUERY(cmdID,false)${if(cmd_statePos>0) ">>"+cmd_statePos.toString else ""})&${genMask(cmd_stateLen)})==0)
    {QUERY(cmdID,false);}
}
"""
    }

    override def genFontEndISA(header_def_str:StringBuilder,header_impl_str:StringBuilder):Unit=
    {
        genFontEndISADef(header_def_str)
        genFontEndISAImpl(header_impl_str)
        genIfScheFreeFunc(header_def_str=header_def_str,header_impl_str=header_impl_str)
    }
    
}
