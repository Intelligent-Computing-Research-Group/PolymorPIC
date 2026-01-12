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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer

import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.util.Try

case class ParamField(
            name:String,
            begin: Int,
            len: Int,
            annotation: String="",
            enInISA: Boolean=false,  // If true, it indicates that the ISA on the C side will provide data according to this field.
            hasFixValue: Boolean=false,    // If true, it indicates that the ISA on the C side will provide a fixed value, such as a module ID.
            fixValue: Int=0,
            limitation: (Int,Int)=(0,0),       // If this value is not (0,0), then the C code must check the input size and ensure it does not exceed this value. Generally, this is used when the bit width is sufficient but a specific size limit is needed.       
            )
{
    val end=begin+len-1
    var sub_fields_seq: Seq[ParamField] = Seq.empty

    def addField(len:Int,name:String,annotation:String="",enInISA:Boolean=false,hasFixValue:Boolean=false,fixValue:Int=0): Unit = {
        require(len<=len)
        
        if(sub_fields_seq.nonEmpty)
        {
            val lastField = sub_fields_seq.last
            sub_fields_seq=sub_fields_seq :+ ParamField(name=name,
                                                        len=len,
                                                        begin=lastField.end+1,
                                                        annotation=annotation,
                                                        enInISA=enInISA,hasFixValue=hasFixValue,fixValue=fixValue,limitation=limitation)
        }
        else
        {
            sub_fields_seq=sub_fields_seq :+ ParamField(name=name,
                                                        len=len,
                                                        begin=begin,
                                                        annotation=annotation,
                                                        enInISA=enInISA,hasFixValue=hasFixValue,fixValue=fixValue,limitation=limitation)
        }
    }

    // Get current node by name.
    def getField(name:String): ParamField={
        sub_fields_seq.find(_.name == name).getOrElse(throw new NoSuchElementException(name))
    }

    // Get leaf node(s)
    def getAllSubField():Seq[ParamField]={
        var res_seq: Seq[ParamField] = Seq.empty
        if(sub_fields_seq.isEmpty)  // empty means it is a leaf field
        {
            res_seq=res_seq :+ this
        }
        else
        {
            sub_fields_seq.foreach{ elem=>
                res_seq=res_seq++elem.getAllSubField()
            }
        }       
        res_seq
    }

    def extract(din:UInt):UInt={din(end,begin)}

    def extract(din:UInt,parentPos:(Int,Int)):UInt={
        val refEnd=parentPos._1
        val refBegin=parentPos._2
        require(refEnd>=end,"Error")
        require(refBegin<=begin,"Error")
        din(end-refBegin,begin-refBegin)   
    }
}


trait ISA_Info_new{
    val name:String
    val describe:String
    val sysCfg:Sys_Config
    val return_rd:Boolean

    // setReg
    val enSetSrc:Boolean
    val setSrcInfo:SetSrcInfo = new SetSrcInfo(sysCfg)
    val enSetDst:Boolean
    val setDstInfo:SetDstInfo = new SetDstInfo(sysCfg)
    val enSetSize:Boolean
    val setSizeInfo:SetSizeInfo = new SetSizeInfo(sysCfg)
    val enSetParams:Boolean
    val setParamAndRunInfo:SetParamAndRunInfo = new SetParamAndRunInfo(sysCfg)


    // Add ISA
    // Add by reg segment characteristics.
    def addISASetRegInfo(setRegType:String,regSubField:String="",
                        name:String,len:Int,annotation:String="",
                        limitation:(Int,Int)=(0,0),
                        hasFixValue:Boolean=false,fixValue: Int=0)=
    {
        def setSrcInfoAddField():Unit={
            require(enSetSrc==true,"The reg set not enabled!")
            setSrcInfo.rootField.getField("srcAddress").
                addField(enInISA=true,len=len,name=name,annotation=annotation)
        }

        def setDstInfoAddField():Unit={
            require(enSetDst==true,"The reg set not enabled!")
            setDstInfo.rootField.getField("dstAddress").
                addField(enInISA=true,len=len,name=name,annotation=annotation)
        }

        def setSizeInfoAddField():Unit={
            require(enSetSize==true,"The reg set not enabled!")
            setSizeInfo.rootField.getField(regSubField).
                addField(enInISA=true,len=len,name=name,annotation=annotation)
        }

        def setParamInfoAddField():Unit={
            require(enSetParams==true,"The reg set not enabled!")
            setParamAndRunInfo.rootField.getField(regSubField).
                addField(enInISA=true,len=len,name=name,annotation=annotation,
                        hasFixValue=hasFixValue,fixValue=fixValue)
        }

        setRegType match {
            case "srcAddr" => setSrcInfoAddField()
            case "dstAddr" => setDstInfoAddField()
            case "size"    => setSizeInfoAddField()
            case "params"   => setParamInfoAddField()
            case _         => throw new IllegalArgumentException(s"Unknown reg field:${setRegType}")
        }
    }

    def addModuleID_fixVal(moduleID:Int):Unit=
    {
        addISASetRegInfo(setRegType="params",regSubField="moduleID",
                    // ISA size field info
                    name="funcID",
                    len=log2Ceil(FuncModule.totalModule),
                    annotation="Which function to run.",
                    hasFixValue=true,fixValue=moduleID)
    }

    def getISAField(regInfo:SetRegRule):Seq[ParamField]=
    {
        regInfo.getAllISAFields()
    }

    def getAllISAField():Seq[ParamField]=
    {
        var res_seq: Seq[ParamField] = Seq.empty
       
        if(enSetSrc)
        {
            val enSetSrcISAFields=getISAField(setSrcInfo)
            require(!enSetSrcISAFields.isEmpty,"The reg info part is empty. Check whether the reg is enabled in ISA define.")
            res_seq=res_seq++enSetSrcISAFields
        }

        if(enSetDst)
        {
            val enSetDstISAFields=getISAField(setDstInfo)
            require(!enSetDstISAFields.isEmpty,"The reg info part is empty. Check whether the reg is enabled in ISA define.")
            res_seq=res_seq++enSetDstISAFields
        }

        if(enSetSize)
        {
            val enSetSizeISAFields=getISAField(setSizeInfo)
            require(!enSetSizeISAFields.isEmpty,"The reg info part is empty. Check whether the reg is enabled in ISA define.")
            res_seq=res_seq++enSetSizeISAFields
        }

        require(enSetParams==true,"The reg set must enable!")
        if(enSetParams)
        {
            val enSetParamsISAFields=getISAField(setParamAndRunInfo)
            require(!enSetParamsISAFields.isEmpty,"The reg info part is empty. Check whether the reg is enabled in ISA define.")
            res_seq=res_seq++enSetParamsISAFields
        }

        res_seq
    }


    // Generate C code to concatenate frontend ISA fields and pass them to the setReg function.
    def genSetRegFuncCode(regInfo:SetRegRule,header_str:StringBuilder):Unit=
    {
        // All parameters needed for setReg
        val all_needed_param=regInfo.rootField.sub_fields_seq

        // Calculate the number of ISA frontend parameters corresponding to each parameter
        val font_end_param_num_seq=all_needed_param.map(_.sub_fields_seq.length)
        // Calculate the mapping between each parameter position and the corresponding frontend ISA parameters, which are concatenated to form allISAField_seq in genFrontendISA.
        val font_end_paramSeq_seq=all_needed_param.map(_.sub_fields_seq)

        // param info comb
        val zippedParamIndfo: Seq[(Seq[ParamField],Int)] = font_end_paramSeq_seq.zip(font_end_param_num_seq)

        regInfo.genCalCodeForC(zippedParamIndfo=zippedParamIndfo,
                                header_str=header_str,
                                needReturn=return_rd)
    }

    // Generates a parameter list. The second returned value (Int) indicates the number of parameters to be passed.
    def genParamList(): String =
    {
        // All ISA fields
        val allISAField_seq=getAllISAField()
        val numOfFields = allISAField_seq.length

        // --------Gen C function parma list------
        val field_dtype_seq: Seq[String] = allISAField_seq.map { field =>
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

        var param_list: Seq[String] = Seq.empty
        var param_anno_list: Seq[String] = Seq.empty
        var validParam=0
        for(i<- 0 until numOfFields)
        {
            // First, if the field has a fixed value, skip it and do not add it to the parameter list
            if(!allISAField_seq(i).hasFixValue)
            {
                validParam+=1
                param_list=param_list :+ ("\t"+field_dtype_seq(i)+" "+allISAField_seq(i).name)

                // anno
                if(allISAField_seq(i).annotation=="")
                {param_anno_list=param_anno_list :+ allISAField_seq(i).annotation}
                else
                {{param_anno_list=param_anno_list :+ ("// "+allISAField_seq(i).annotation) }}
            }
        }

        // make it a string
        var param_list_string:String=""
        for(i<- 0 until validParam)
        {
            param_list_string=param_list_string+param_list(i)
            if(i!=validParam-1){param_list_string=param_list_string+","}

            param_list_string=param_list_string+"\t"+param_anno_list(i)+"\n"
        }

        return param_list_string
    }

    // Gen func definition
    def genFuncDef():String=
    {
        // Set return type
        val return_type=if(!return_rd){"void"}else{"uint64_t"}
        var func_def:String=""
        func_def=func_def+s"${return_type} ${name}(\n${genParamList()})"
        return func_def
    }

    // Generate the definition of the overall ISA function
    def genFontEndISADef(header_def_str:StringBuilder):Unit=
    {
        header_def_str++=s"\n"
        header_def_str++=genFuncDef()+";"
        header_def_str++=s"\n"
    }

    // Generate the implementation of the overall ISA function
    def genFontEndISAImpl(header_impl_str:StringBuilder):Unit=
    {
        header_impl_str++=s"\n"
        header_impl_str++=genFuncDef()
        header_impl_str++="{\n"

        // srcAddr
        if(enSetSrc)
        {
            genSetRegFuncCode(setSrcInfo,header_impl_str)
        }

        // dstAddr
        if(enSetDst)
        {
            genSetRegFuncCode(setDstInfo,header_impl_str)
        }

        // size
        if(enSetSize)
        {
            genSetRegFuncCode(setSizeInfo,header_impl_str)
        }

        // others
        require(enSetParams==true,"The reg set must enable!")
        if(enSetParams)
        {
            genSetRegFuncCode(setParamAndRunInfo,header_impl_str)
        }

        header_impl_str++="}\n"

        header_impl_str++=s"\n"
    }

    // Generate the overall ISA function including definition and implementation
    def genFontEndISA(header_def_str:StringBuilder,header_impl_str:StringBuilder):Unit=
    {
        genFontEndISADef(header_def_str)
        genFontEndISAImpl(header_impl_str)
    }


    def genMask(width:Int):Int=
    {
        return Math.pow(2,width).toInt-1
    }

}

// Corresponds to multiple ISA destination objects.
object FuncModule
{
    val LOAD_ID=0
    val P2SL_ID=1
    val P2SR_ID=2
    val P2SRT_ID=3
    val IM2COL_ID=4
    val ACC_ID=5
    val EXE_ID=6
    val STORE_ID=7
    val SWITCH_ID=8
    val QUERY_ID=9

    val totalModule=10
}


case class NewISA(sysCfg:Sys_Config)
{
    val setRegsInfo=new SetRegsInfo(sysCfg)

    val LOAD_info=ISA_LOAD(sysCfg=sysCfg)
    val P2SL_info=ISA_P2SL(sysCfg=sysCfg)
    val P2SR_info=ISA_P2SR(sysCfg=sysCfg)
    val IM2COL_info=ISA_IM2COL(sysCfg=sysCfg)
    val ACC_info=ISA_ACC(sysCfg=sysCfg)
    val EXE_info=ISA_EXE(sysCfg=sysCfg)
    val STORE_info=ISA_STORE(sysCfg=sysCfg)
    val SWITCH_info=ISA_SWITCH(sysCfg=sysCfg)
    val QUERY_info=ISA_QUERY(sysCfg=sysCfg)

    
    
    def genHeaderFile():Unit=
    {
        val header_def_str = new StringBuilder()
        val header_def_path=Paths.get(sysCfg.header_def_path)

        var parentDir = header_def_path.getParent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        val header_impl_str = new StringBuilder()
        val header_impl_path=Paths.get(sysCfg.header_impl_path)

        parentDir = header_impl_path.getParent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }


        // include in head file 
        header_def_str++="#include <stdint.h>\n"
        header_def_str++="#include <stdbool.h>\n"
        header_def_str++="#include <stdio.h>\n"
        header_def_str++="#include \"rocc.h\"\n"
        header_def_str++="\n"

        header_impl_str++="#include \"ISA.h\"\n"
        header_impl_str++="\n"

        // setReg Functions

        // some necessary define
        val rocc_code_name="ROCC_OPCODE"
        val rocc_code_val=0
        header_def_str++=s"#define ${rocc_code_name} ${rocc_code_val}\n"
        header_def_str++=s"\n"
        header_def_str++=s"#define ${CalInfo.ACC_32BIT_define_str} ${CalInfo.ACC_32BIT_define_val}\n"
        header_def_str++=s"#define ${CalInfo.ACC_16BIT_define_str} ${CalInfo.ACC_16BIT_define_val}\n"
        header_def_str++=s"\n"
        header_def_str++=s"\n// ----- Mode Switch Information -----\n"
        header_def_str++=s"#define ALLOC ${PIC_Switch.ALLOC_define}\n"
        header_def_str++=s"#define FREE ${PIC_Switch.FREE_define}\n"
        header_def_str++=s"#define PIC_MODE true\n"
        header_def_str++=s"#define MAT_PER_LEVEL ${sysCfg.nMat_per_level}\n"
        header_def_str++=s"#define CACHE_SIZE_KB ${(sysCfg.cacheSizeBytes/1024).toInt}\n"
        header_def_str++=s"#define SUB_ARRAY_WORLD_LINE_NUM ${(sysCfg.core_config.wordlineNums).toInt}\n"
        header_def_str++=s"#define MAT_SIZE_KB ${(sysCfg.matSizeBytes/1024).toInt}\n"
        header_def_str++=s"#define TOTAL_LEVEL ${sysCfg.total_levels}\n"
        header_def_str++=s"\n"

        setRegsInfo.genHead(header_def_str,header_impl_str)

        header_impl_str++=s"\n"
        header_impl_str++=s"/*----------------Frontend ISA implementation---------------*/\n"
        header_impl_str++=s"\n"

        LOAD_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        P2SL_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        P2SR_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        IM2COL_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        ACC_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        EXE_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        STORE_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        SWITCH_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        QUERY_info.genFontEndISA(header_def_str=header_def_str,header_impl_str=header_impl_str)

        Files.write(header_def_path, header_def_str.toString().getBytes(StandardCharsets.UTF_8))
        Files.write(header_impl_path, header_impl_str.toString().getBytes(StandardCharsets.UTF_8))
    
    
        // Copy to benchmark
        sysCfg.header_cpy_Paths.foreach { targetPathString =>
            val targetPathDef = Paths.get(targetPathString).resolve(header_def_path.getFileName.toString)
            val targetPathImpl = Paths.get(targetPathString).resolve(header_impl_path.getFileName.toString)
            try {
                Files.copy(header_def_path, targetPathDef, StandardCopyOption.REPLACE_EXISTING)
                Files.copy(header_impl_path, targetPathImpl, StandardCopyOption.REPLACE_EXISTING)
                println(s"ISA copied to $targetPathString successfully.")
            } catch {
                case e: Exception => println(s"Failed to copy ISA to $targetPathString: ${e.getMessage}")
            }
        }
    
    }
}