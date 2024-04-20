package PIC

import chisel3._
import chisel3.util._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer

import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.util.Try


case class Field(
            loc:String,
            name:String,
            end: Int,
            len: Int,
            annotation: String="",
            prepocess: String="None"
            )
{
    val begin=end-len+1
    var sub_fields_seq: Seq[Field] = Seq.empty

    def addField(len:Int,name:String,annotation:String="",prepocess: String="None"): Unit = {


        if(sub_fields_seq.nonEmpty)
        {
            val lastField = sub_fields_seq.last
            sub_fields_seq=sub_fields_seq :+ Field(loc=loc,name=name,end=lastField.begin-1,len=len,annotation=annotation,prepocess=prepocess)
        }
        else
        {
            sub_fields_seq=sub_fields_seq :+ Field(loc=loc,name=name,end=end,len=len,annotation=annotation,prepocess=prepocess)
        }
    }

    def getField(name:String): Field={
        sub_fields_seq.find(_.name == name).getOrElse(throw new NoSuchElementException("2"))
    }

    def getAllSubField():Seq[Field]={
        var res_seq: Seq[Field] = Seq.empty
        if(sub_fields_seq.isEmpty && name!="rs1" && name!="rs2")  // empty means it is a leaf field
        {
            res_seq=res_seq :+ this
        }
        else if(!sub_fields_seq.isEmpty)
        {
            sub_fields_seq.foreach{ elem=>
                res_seq=res_seq++elem.getAllSubField()
            }
        }       
        res_seq
    }

    // For signal connection
    def extractVal(rs1:UInt,rs2: UInt):UInt={Cat(rs1,rs2)(end,begin)}
}

trait ISA_Info{
    val RS1:Field
    val RS2:Field
    val name:String
    val opcode:UInt
    val sysCfg:Sys_Config
    val return_rd:Boolean

    def getAllFields():Seq[Field]=
    {
        var res_seq: Seq[Field] = Seq.empty
        // get all field form rs1
        res_seq=res_seq++RS1.getAllSubField()
        // get all field form rs2
        res_seq=res_seq++RS2.getAllSubField()

        res_seq
    }

    def getHeaderContents(header_str:StringBuilder,rocc_code_name:String):StringBuilder=
    {
        // -------- Get field ------
        val fields_seq=getAllFields()
        val numOfFields = fields_seq.length
        val rs1_fields_seq = fields_seq.filter(_.loc == "rs1")
        val rs2_fields_seq = fields_seq.filter(_.loc == "rs2")
        val numOfRs1Field=rs1_fields_seq.length
        val numOfRs2Field=rs2_fields_seq.length

        
        // --------Gen C function head------
        val field_dtype_seq: Seq[String] = fields_seq.map { field =>
            assert(field.len>0,"What's matter with you?")
            field.len match {
                case len if len > 32 => "uint64_t"
                case len if len <= 32 && len > 16 => "uint32_t"
                case len if len > 8 && len <= 16 => "uint16_t"
                case len if len > 1 && len <= 8 => "uint8_t"
                case len if len == 1  => "bool"
                case _ => "false!"
            }
        }
        assert(numOfFields==field_dtype_seq.length,"Something bad happens!")
        var param_list=""
        for(i<- 0 until numOfFields)
        {
            param_list=param_list+"\t"+field_dtype_seq(i)+" "+fields_seq(i).name
            if(i!=numOfFields-1){param_list=param_list+","}
            // anno
            if(fields_seq(i).annotation!="")
            {param_list=param_list+"\t//"+fields_seq(i).annotation}
            param_list=param_list+"\n"
        }

        val return_type=if(!return_rd){"void"}else{"int"}
        if(numOfFields==0){header_str++=s"${return_type} ${name}()\n"}
        else{header_str++=s"${return_type} ${name}(\n${param_list})\n"}
        header_str++="{\n"

        // ----------Gen rs1 part---------
        var ptr_rs1=63
        if(!rs1_fields_seq.isEmpty)
        {
            header_str++=s"\tuint64_t rs1=0;\n"
            // The first element
            val first_field=rs1_fields_seq(0)
            header_str++=s"\trs1=${first_field.name};\n"
            ptr_rs1=first_field.begin
            for(i<-1 until numOfRs1Field)
            {
                val cur_field=rs1_fields_seq(i)
                val cur_field_len=ptr_rs1-cur_field.begin
                if(rs1_fields_seq(i).prepocess=="-1")
                {header_str++=s"\trs1=(rs1<<${cur_field_len})+${cur_field.name}-1;\n"}
                else
                {header_str++=s"\trs1=(rs1<<${cur_field_len})+${cur_field.name};\n"}
                ptr_rs1=cur_field.begin
            }
            val unsed_bit_rs1=64-rs1_fields_seq.map(_.len).sum
            if(unsed_bit_rs1>0){header_str++=s"\trs1=(rs1<<${unsed_bit_rs1});\n"}
        }

        // blank line
        header_str++=s"\n"

        // ----------Gen rs1 part---------
        var ptr_rs2=31
        if(!rs2_fields_seq.isEmpty)
        {
            header_str++=s"\tuint32_t rs2=0;\n"
            // The first element
            val first_field=rs2_fields_seq(0)
            header_str++=s"\trs2=${first_field.name};\n"
            ptr_rs2=first_field.begin
            for(i<-1 until numOfRs2Field)
            {
                val cur_field=rs2_fields_seq(i)
                val cur_field_len=ptr_rs2-cur_field.begin
                if(rs2_fields_seq(i).prepocess=="-1")
                {header_str++=s"\trs2=(rs2<<${cur_field_len})+${cur_field.name}-1;\n"}
                else
                {header_str++=s"\trs2=(rs2<<${cur_field_len})+${cur_field.name};\n"}
                ptr_rs2=cur_field.begin
            }
            val unsed_bit_rs2=32-rs2_fields_seq.map(_.len).sum
            if(unsed_bit_rs2>0){header_str++=s"\trs2=(rs2<<${unsed_bit_rs2});\n"}
        }

        // blank line
        header_str++=s"\n"

        // --------Gen Rocc------
        var rocc_inst_str:String="" 
        val opcode_int=opcode.litValue.toInt 
        if (!return_rd)
        {
            if(numOfRs1Field > 0 && numOfRs2Field > 0 ) 
            {rocc_inst_str=s"\tROCC_INSTRUCTION_SS(${rocc_code_name},rs1,rs2,${opcode_int});\n";}
            else if (numOfRs1Field > 0 && numOfRs2Field == 0) 
            {rocc_inst_str=s"\tROCC_INSTRUCTION_S(${rocc_code_name},rs1,${opcode_int});\n";} 
            else if (numOfRs1Field == 0 && numOfRs2Field == 0)
            {rocc_inst_str=s"\tROCC_INSTRUCTION(${rocc_code_name},${opcode_int});\n";} 
            else {assert(false,"Have you read the doc about Rocc carefully??")} 
        }
        else
        {
            header_str++=s"\tunsigned long rd = -1;\n"
            if(numOfRs1Field > 0 && numOfRs2Field > 0)
            {rocc_inst_str=s"\tROCC_INSTRUCTION_DSS(${rocc_code_name},rd,rs1,rs2,${opcode_int});\n";}
            else if (numOfRs1Field > 0 && numOfRs2Field == 0)
            {rocc_inst_str=s"\tROCC_INSTRUCTION_DS(${rocc_code_name},rd,rs1,${opcode_int});\n";} 
            else if (numOfRs1Field == 0 && numOfRs2Field == 0)
            {rocc_inst_str=s"\tROCC_INSTRUCTION_D(${rocc_code_name},rd,${opcode_int});\n";} 
            else {assert(false,"Have you read the doc about Rocc carefully??")} 
        }

        header_str++=rocc_inst_str
        // blank line
        header_str++=s"\n"

        if(return_rd){header_str++="\treturn rd;\n"}


        header_str++=s"}\n"
    }

}

case class LOAD_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="LOAD",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{
    // add from HSB to LSB
    RS1.addField(len=32,name="baseDRAM_Addr")
    RS1.addField(len=18,name="basePIC_Addr",annotation="The full addr in PIC, specifying which row.")
    RS1.addField(len=14,name="LoadLen",annotation=s"Load P:${log2Ceil(sysCfg._max_P_block_64b_per_row+1)}bits. How many 64b per row in P_block; P2SR: nCol of R block (len=${log2Ceil(sysCfg.core_config.wordlineNums+1)}b).; P2SL: nRow of L block (len=${log2Ceil(sysCfg.core_config.max_L_rows_per_block+1)}b);")

    RS2.addField(len=32,name="Parameters")      // It will has sub fields
    val param_field=RS2.getField("Parameters")
    param_field.addField(len=sysCfg._R_row_offset_sigLen,name="Offset",annotation=s"${sysCfg._R_row_offset_sigLen}bits. In p2s, it is the num of row(R)/col(L) elems in the whole R/L. In Load P, it is the num of 64b each whole P row.")
    param_field.addField(len=log2Ceil(4),"nBuf",annotation="Num of Bufs configured in each Mat. This param is used exclusively by P2SR.")
    param_field.addField(len=log2Ceil(8),"nBit",annotation="The bit width of elem in R/L. This param is used exclusively by P2SR/L and should -1.(e.g., if width=8, 7->nBit.)")
    param_field.addField(len=log2Ceil(sysCfg.core_config.max_L_rows_per_block+1),name="P_block_row",annotation=s"${log2Ceil(sysCfg.core_config.max_L_rows_per_block+1)}bits. How many rows in P_block. Need to be reduced by one!!Ignore if use p2s!")
    param_field.addField(len=1,"Split",annotation="If conduct P2S. Used by P2SR/L. (Split=1,Transpose=1)=>P2SR  (Split=1,Transpose=0)=>P2SL  (Split=0,Transpose=0)=>LoadP")
    param_field.addField(len=1,"Transpose",annotation="If conduct Transpose. Used by P2SR/L.")

    def getLoadType(rs1: UInt,rs2:UInt): UInt=
    {
        Cat(param_field.getField("Split").extractVal(rs1=rs1,rs2=rs2),param_field.getField("Transpose").extractVal(rs1=rs1,rs2=rs2))
    }
    val LOAD_P="b00".U
    val P2S_R="b11".U
    val P2S_L="b10".U

}

case class STORE_P_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="STORE_P",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{
    // add from HSB to LSB
    RS1.addField(len=32,name="baseDRAM_Addr")
    RS1.addField(len=18,name="basePIC_Addr")
    RS1.addField(len=14,name="P_block_64b_per_row",annotation=s"Store P:${log2Ceil(sysCfg._max_P_block_64b_per_row+1)}bits. How many 64b per row in P_block;")

    RS2.addField(len=32,name="Parameters")
    val param_field=RS2.getField("Parameters")
    param_field.addField(len=sysCfg._R_row_offset_sigLen,name="Offset",annotation=s"${sysCfg._R_row_offset_sigLen}bits. In Store P, it is the num of 64b each whole P row.")
    param_field.addField(len=log2Ceil(sysCfg.core_config.max_L_rows_per_block+1),name="P_block_row",annotation=s"${log2Ceil(sysCfg.core_config.max_L_rows_per_block+1)}bits.How many rows in P_block. Need to be reduced by one!!")
}

case class ACC_INFO(
    override val RS1:Field = Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field = Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="ACC",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val  sysCfg:Sys_Config) extends ISA_Info
{
    // add from HSB to LSB
    RS1.addField(len=18,name="PIC_Addr_src",annotation="The base full address of the src.")
    RS1.addField(len=18,name="PIC_Addr_dest",annotation="The base full address of the dest.")
    RS1.addField(len=16,name="AccLen",annotation=s"The number of row(64b) to  acc. Valid bit width is ${sysCfg._L_row_offset_sigLen}")

    RS2.addField(len=32,name="Parameters")      // It will has sub fields
    val param_field=RS2.getField("Parameters")
    param_field.addField(len=log2Ceil(sysCfg.acc_res_num+1),name="SrcNum",annotation="The SrcNum. Each src is located is the same relative address in the neibour mat!")
    param_field.addField(len=1,name="LoadDest",annotation="If it needs to load the value from dest and add it to src.")
    param_field.addField(len=1,name="BitWidth",annotation="The bitWdth of elem. 1->32b,0->16b")
    param_field.addField(len=1,name="If_last",annotation=s"One bit, if it is the last store of the calculation.")
    param_field.addField(len=1,name="need_P",annotation=s"One bit, if it need to load p from dram.")
}

case class EXE_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="EXE",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{
    // add from HSB to LSB
    RS1.addField(len=18,name="PIC_Addr_R",annotation="Which mat to activate calculation. Only need matID, not full addr.")
    RS1.addField(len=18,name="PIC_Addr_L",annotation="Where is the first L row saved.")
    RS1.addField(len=16,name="R_Valid_nCols",annotation="How many R cols.(In the array, it is the num of valid row of R)",prepocess="-1")

    RS2.addField(len=32,name="Parameters")      // It will has sub fields
    val param_field=RS2.getField("Parameters")
    param_field.addField(len=2,name="nBuf",annotation="How many arrays used as buffer to keep P's partial sum")
    param_field.addField(len=2,name="nCal",annotation="How many arrays used to conduct MAC.(The left array except used as buffers will be MAC mode.However, not all of them must work.)")
    param_field.addField(len=log2Ceil(8),name="Base_R_Bit",annotation="The first R bit slice's bit position. The left R bit slices are +1 one by one based on it.")
    param_field.addField(len=log2Ceil(8),name="L_Precision",annotation="What is L bitWidth. It should -1. e.g., if it is 8b, write 7 here")
    param_field.addField(len=log2Ceil(sysCfg.core_config.max_L_rows_per_block),name="L_Block_Row",annotation="How many row in L.")
    param_field.addField(len=1,name="Sign",annotation="If value is signed.")
    param_field.addField(len=1,name="BitWidth",annotation=s"The accWidth. 32b->${CalInfo.ACC_32BIT_define_val};16b->${CalInfo.ACC_16BIT_define_val}")
}

case class QUERY_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="QUERY_STATE",
    override val opcode:UInt,
    override val return_rd:Boolean=true,
    override val sysCfg:Sys_Config) extends ISA_Info
{
}

case class PIC_SWITCH_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="PIC_SWITCH",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{

    RS1.addField(len=1,name="opType",annotation=s"OpType. ${PIC_Switch.ALLOC} is alloc, ${PIC_Switch.FREE} is FREE.")
    RS1.addField(len=log2Ceil(sysCfg.total_levels),name="nLevels",annotation=s"How many clusters of mats to Alloc. Total ${sysCfg.pic_avail_levels} is available.")
}

case class SAVE_TRACE_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="SAVE_TRACE",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{
    RS1.addField(len=sysCfg.accessBankFullAddr_sigLen,name="pic_dest_addr",annotation=s"Full pic addr.")
}

case class INIT_INFO(
    override val RS1:Field= Field(loc="rs1",name="rs1",end=95,len=64),
    override val RS2:Field= Field(loc="rs2",name="rs2",end=31,len=32),
    override val name:String="INIT",
    override val opcode:UInt,
    override val return_rd:Boolean=false,
    override val sysCfg:Sys_Config) extends ISA_Info
{
}



case class ISA(sysCfg:Sys_Config)
{
    // opcode
    val LOAD=0.U
    val STORE_P=1.U
    val EXE=2.U
    val ACC=3.U
    val QUERY=4.U           // immed
    val PIC_SWITCH=5.U    // immed
    val SAVE_TRACE=6.U    // immed
    val INIT=7.U    // immed

    val LOAD_info=LOAD_INFO(opcode=LOAD,sysCfg=sysCfg)
    val STORE_P_info=STORE_P_INFO(opcode=STORE_P,sysCfg=sysCfg)
    val ACC_info=ACC_INFO(opcode=ACC,sysCfg=sysCfg)
    val EXE_info=EXE_INFO(opcode=EXE,sysCfg=sysCfg)
    val QUERY_info=QUERY_INFO(opcode=QUERY,sysCfg=sysCfg)
    val PIC_SWITCH_info=PIC_SWITCH_INFO(opcode=PIC_SWITCH,sysCfg=sysCfg)
    val SAVE_TRACE_info=SAVE_TRACE_INFO(opcode=SAVE_TRACE,sysCfg=sysCfg)
    val INIT_info=INIT_INFO(opcode=INIT,sysCfg=sysCfg)


    def genHeaderFile():Unit=
    {
        val header_str = new StringBuilder()
        val header_path=Paths.get(sysCfg.header_path)
        val parentDir = header_path.getParent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        // include
        header_str++="#include <stdint.h>\n"
        header_str++="#include <stdbool.h>\n"
        header_str++="#include \"rocc.h\"\n"
        header_str++="\n"

        // define
        val rocc_code_name="ROCC_OPCODE"
        val rocc_code_val=0
        header_str++=s"#define ${rocc_code_name} ${rocc_code_val}\n"
        header_str++=s"\n"
        header_str++=s"#define ${CalInfo.ACC_32BIT_define_str} ${CalInfo.ACC_32BIT_define_val}\n"
        header_str++=s"#define ${CalInfo.ACC_16BIT_define_str} ${CalInfo.ACC_16BIT_define_val}\n"
        if(sysCfg.en_trace)
        { header_str++=s"#define TRACE_EN 1\n" }
        header_str++=s"#define TRACE_DEPTH ${sysCfg.traceCfg.trace_depth}\n"

        header_str++=s"\n"
        LOAD_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        STORE_P_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        ACC_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        EXE_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        QUERY_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        PIC_SWITCH_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        SAVE_TRACE_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"
        INIT_info.getHeaderContents(header_str,rocc_code_name=rocc_code_name)
        header_str++=s"\n"


        Files.write(header_path, header_str.toString().getBytes(StandardCharsets.UTF_8))

        // Copy
        val sourceFilePath = header_path
        sysCfg.header_cpy_Paths.foreach { targetPathString =>
            val targetPath = Paths.get(targetPathString).resolve(sourceFilePath.getFileName.toString)
            try {
                Files.copy(sourceFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                println(s"File copied to $targetPathString successfully.")
            } catch {
                case e: Exception => println(s"Failed to copy file to $targetPathString: ${e.getMessage}")
            }
        }
    }
}


