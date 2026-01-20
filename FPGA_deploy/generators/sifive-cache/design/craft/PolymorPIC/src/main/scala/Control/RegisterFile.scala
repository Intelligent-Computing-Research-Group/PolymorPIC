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
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._

// Register Files's Info is deined here
class RegsInfo(sysCfg:Sys_Config) extends Bundle
{   
    // Address
    val offChipAddrSrc=UInt(sysCfg.virtualAddrLen.W)
    val offChipAddrDst=UInt(sysCfg.virtualAddrLen.W)
    val onChipAddrSrc=UInt(sysCfg.virtualAddrLen.W)
    val onChipAddrDst=UInt(sysCfg.virtualAddrLen.W)

    // Size
    val row=UInt(sysCfg._ISA_nRow_sigLen.W)
    val bytePerRow=UInt(sysCfg._ISA_bytePerRow_sigLen.W)
    val offset=UInt(sysCfg.offset_signLen.W)

    // Which module
    val moduleID=UInt(log2Ceil(FuncModule.totalModule).W)
    // cmdID
    val cmdID=UInt((sysCfg.cmdID_sigLen).W)
    // Other Parameters
    val others=UInt((64-moduleID.getWidth-cmdID.getWidth).W)

}


class RegFile(sysCfg:Sys_Config)(implicit val p: Parameters) extends Module
{
    val setRegInfo=NewISA(sysCfg).setRegsInfo
    val io=IO(new Bundle {
        val setRegInput=Input(UInt(64.W))

        // sel
        val setSrc=Input(Bool())
        val setDst=Input(Bool())
        val setSize=Input(Bool())
        val setParam=Input(Bool())

        val regOut=Output(new RegsInfo(sysCfg))
    })

    val registerFiles=Reg(new RegsInfo(sysCfg))
    val din=io.setRegInput
    
    when(io.setSrc)
    {
        registerFiles.offChipAddrSrc    :=  setRegInfo.setSrcInfo.rootField.extract(din)
        registerFiles.onChipAddrSrc     :=  setRegInfo.setSrcInfo.rootField.extract(din)
    }

    when(io.setDst)
    {
        registerFiles.offChipAddrDst    :=  setRegInfo.setDstInfo.rootField.extract(din)
        registerFiles.onChipAddrDst     :=  setRegInfo.setDstInfo.rootField.extract(din)
    }

    when(io.setSize)
    {
        val fieldInfo=setRegInfo.setSizeInfo.rootField
        registerFiles.row           :=  fieldInfo.getField("row").extract(din)
        registerFiles.bytePerRow    :=  fieldInfo.getField("bytePerRow").extract(din)
        registerFiles.offset        :=  fieldInfo.getField("offset").extract(din)
    }

    when(io.setParam)
    {
        val fieldInfo=setRegInfo.setParamInfo.rootField
        registerFiles.moduleID      :=  fieldInfo.getField("moduleID").extract(din)
        registerFiles.cmdID         :=  fieldInfo.getField("cmdID").extract(din)
        registerFiles.others        :=  fieldInfo.getField("others").extract(din)
    }

    io.regOut:=registerFiles

}

trait SetRegRule
{
    val name: String
    val totalLen: Int = 64
    val sysCfg:Sys_Config
    val regInfo: RegsInfo = new RegsInfo(sysCfg)
    val rootField : ParamField= ParamField(name="root",begin=0,len=totalLen)
    val roccOpCode:Int
    val isFireFunc:Boolean =false


    // Leaf node
    def getAllFields():Seq[ParamField]=
    {
        var res_seq: Seq[ParamField] = Seq.empty
        // get all field form rs1
        rootField.getAllSubField()
    }

    def getAllISAFields():Seq[ParamField]=
    {
        val reg_fields_seq=getAllFields()
        val isa_field_seq: Seq[ParamField]=reg_fields_seq.filter(field=>field.enInISA)

        isa_field_seq
    }

    def getHeaderContents(return_rd:Boolean=false,header_def:StringBuilder,header_impl:StringBuilder):Unit=
    {
        // -------- Get field ------
        val fields_seq=getAllFields().reverse
        val numOfFields = fields_seq.length

        // --------Gen C function parma list------
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


        val return_type=if(!return_rd){"void"}else{"uint64_t"}
        if(numOfFields==0)
        {
            header_def++=s"${return_type} ${name}()\n"
            header_impl++=s"${return_type} ${name}()\n"
        }
        else
        {
            header_def++=s"${return_type} ${name}(\n${param_list});\n"
            header_impl++=s"${return_type} ${name}(\n${param_list})\n"
        }
        header_impl++="{\n"


        var ptr_rs1=63
        header_impl++=s"\tuint64_t rs1=0;\n"
        // The first element
        val first_field=fields_seq(0)
        header_impl++=s"\trs1=${first_field.name};\n"
        ptr_rs1=first_field.begin
        for(i<-1 until numOfFields)
        {
            val cur_field=fields_seq(i)
            val cur_field_len=ptr_rs1-cur_field.begin
            header_impl++=s"\trs1=(rs1<<${cur_field_len})+${cur_field.name};\n"
            ptr_rs1=cur_field.begin
        }

        // blank line
        header_impl++=s"\n"
        if (!return_rd)
        {
            header_impl++=s"\tROCC_INSTRUCTION_S(${sysCfg.rocc_code_name},rs1,${roccOpCode});\n";
        }
        else
        {
            header_impl++="\tunsigned long rd = -1;\n"
            header_impl++=s"\tROCC_INSTRUCTION_DS(${sysCfg.rocc_code_name},rd,rs1,${roccOpCode});\n";
            header_impl++="\treturn rd;\n"
        }

        header_impl++=s"}\n\n"
    }

    // If 0, the segment is unused; fill with zeros.
    // If greater than 0, concatenation is required.
    // For each subfield, fill in the fixed value if it exists.
    def genCalCodeForC(zippedParamIndfo: Seq[(Seq[ParamField],Int)],header_str:StringBuilder,needReturn:Boolean):Unit=
    {
        // For each subfield, fill in the fixed value if it exists.
        def getParamText(paramField:ParamField):String=
        {
            if(paramField.hasFixValue)
            {return s"${paramField.fixValue}"}
            else
            {return paramField.name}
        }

        val regFieldSeq=rootField.sub_fields_seq
        var paramList: Seq[String] = Seq.empty[String]
        zippedParamIndfo.zipWithIndex.foreach{ case ((subFiledSeq,num),index)=>
            if(num==0)
            {
                paramList=paramList:+ "0"
            }
            else
            {
                val reversedSubField=subFiledSeq.reverse

                val cmbValName=s"${regFieldSeq(index).name}_cmb"
                paramList=paramList:+ cmbValName

                // concatenated variable
                header_str++=s"\t${getCType(regFieldSeq(index).len)} ${cmbValName}=${getParamText(reversedSubField(0))};\n"
                for(i<-1 until num)
                {
                    header_str++=s"\t${cmbValName}=(${cmbValName}<<${reversedSubField(i).len})+${getParamText(reversedSubField(i))};\n"
                }
            }
        }

        var paramListStr=""
        // Because the parameters are reversed, reverse them again when calling
        paramList=paramList.reverse
        for(i<- 0 until paramList.length)
        {
            paramListStr=paramListStr+paramList(i)
            if(i!=paramList.length-1){paramListStr=paramListStr+","}
        }

        assert(!(needReturn==true&&isFireFunc==false),"If the setReg func is not a decision-to-run function, can't return its returned value!")
        if(needReturn)
        {   header_str++=s"\treturn ${name}(${paramListStr});\n"   }
        else
        {   header_str++=s"\t${name}(${paramListStr});\n"   }

    }

    def getCType(len: Int): String = {
        len match {
            case l if l > 32 => "uint64_t"
            case l if l <= 32 && l > 16 => "uint32_t"
            case l if l > 8 && l <= 16 => "uint16_t"
            case l if l > 1 && l <= 8 => "uint8_t"
            case l if l == 1  => "bool"
            case _ => "false!"
        }
    }
}

// Define the content and name of each register.
class SetSrcInfo(val sysCfg: Sys_Config) extends SetRegRule
{
    val name="setSrcInfo"
    val roccOpCode: Int = 0
    rootField.addField(len=sysCfg.virtualAddrLen,name="srcAddress")

}

class SetDstInfo(val sysCfg: Sys_Config) extends SetRegRule
{
    val name="setDstInfo"
    val roccOpCode: Int = 1
    rootField.addField(len=sysCfg.virtualAddrLen,name="dstAddress")
}

class SetSizeInfo(val sysCfg: Sys_Config) extends SetRegRule
{
    val name="setSizeInfo"
    val roccOpCode: Int = 2
    rootField.addField(len=regInfo.row.getWidth,name="row")
    rootField.addField(len=regInfo.bytePerRow.getWidth,name="bytePerRow")
    rootField.addField(len=regInfo.offset.getWidth,name="offset")
}


class SetParamAndRunInfo(val sysCfg: Sys_Config) extends SetRegRule
{
    val name="setParamInfo"
    val roccOpCode: Int = 3
    rootField.addField(len=regInfo.moduleID.getWidth,name="moduleID")
    rootField.addField(len=regInfo.cmdID.getWidth,name="cmdID")
    rootField.addField(len=regInfo.others.getWidth,name="others")

    override val isFireFunc=true
}

class SetRegsInfo(val sysCfg: Sys_Config)
{
    val setSrcInfo=new SetSrcInfo(sysCfg)
    val setDstInfo=new SetDstInfo(sysCfg)
    val setSizeInfo=new SetSizeInfo(sysCfg)
    val setParamInfo=new SetParamAndRunInfo(sysCfg)

    def genHead(header_def:StringBuilder,header_impl:StringBuilder):Unit=
    {
        setSrcInfo.getHeaderContents(header_def=header_def,header_impl=header_impl)
        setDstInfo.getHeaderContents(header_def=header_def,header_impl=header_impl)
        setSizeInfo.getHeaderContents(header_def=header_def,header_impl=header_impl)
        setParamInfo.getHeaderContents(return_rd=true,header_def=header_def,header_impl=header_impl)
    }
}