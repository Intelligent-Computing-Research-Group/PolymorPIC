
package PIC

import chisel3._
import chisel3.util._


object ModeInfo
{
    val Mode_bitWidth=2
    val PICMode_Mem = "b00".U
    val PICMode_Mac = "b11".U
    val PICMode_IdleMac = "b01".U

    // def isCacheMode(mode: UInt): Bool = { mode===CacheMode }
    def isPICMode_Mac(mode: UInt): Bool = { mode===PICMode_Mac }
    def isPICMode_Mem(mode: UInt): Bool = { mode===PICMode_Mem }
    def isPICMode_IdleMac(mode: UInt): Bool = { mode===PICMode_IdleMac }
}

object CalInfo
{
  val ACC_32BIT=true.B
  val ACC_16BIT=false.B

  val ACC_32BIT_define_str="ACC_32BIT"
  val ACC_32BIT_define_val="true"
  val ACC_16BIT_define_str="ACC_16BIT"
  val ACC_16BIT_define_val="false"

}

case class PolymorPIC_Kernal_Config(
                                // custom opcode type
                                //opcodes: OpcodeSet = OpcodeSet.custom0,
                                // How many array in each mat
                                arrayNums: Int = 4,
                                // The size of each array
                                wordlineNums: Int = 1024,   // row
                                bitlineNums: Int = 64,      // col
                                total_mats:Int=32
                                                       ) 
{
    val array_addrWidth=log2Ceil(wordlineNums)
    val vec_len=bitlineNums
    //同时影响最大一次ld/st的p的行数
    val max_L_rows_per_block=512 //(16*(arrayNums-1)*bitlineNums/16).toInt
    // Assert vec_len%32and16==0
    val segNum_in_per_word=(bitlineNums/16).toInt
    val _L_vec_fetch_addrLen=log2Ceil(total_mats)+log2Ceil(arrayNums)+log2Ceil(wordlineNums)

    val total_arrays=total_mats*4

    val total_64b=total_mats*4*wordlineNums
    val core_addrLen=log2Ceil(arrayNums*wordlineNums)
}