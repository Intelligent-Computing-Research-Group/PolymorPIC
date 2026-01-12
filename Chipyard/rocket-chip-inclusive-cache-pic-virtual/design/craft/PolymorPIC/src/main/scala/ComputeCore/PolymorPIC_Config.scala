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
                                // How many array in each mat
                                arrayNums: Int = 4,
                                // The size of each array
                                wordlineNums: Int = 512,   // row
                                bitlineNums: Int = 64,      // col
                                cacheSizeBytes: Int,
                                                       ) 
{
    // Calculate the total number of Mats in the system, including parts that cannot be switched to PIC mode (calculated as they are also divided to Mats).
    val matSizeBytes=(arrayNums*wordlineNums*bitlineNums/8).toInt
    assert(arrayNums*wordlineNums*bitlineNums%8==0,"Something bad happens.")
    val total_mats=(cacheSizeBytes/matSizeBytes).toInt
    assert(cacheSizeBytes%matSizeBytes==0,"Something bad happens.")

    val array_addrWidth=log2Ceil(wordlineNums)
    val vec_len=bitlineNums
    // Also affects the maximum number of rows of p loaded/stored at one time
    val max_L_rows_per_block=wordlineNums //(16*(arrayNums-1)*bitlineNums/16).toInt
    // Assert vec_len%32and16==0
    val segNum_in_per_word=(bitlineNums/16).toInt
    val _L_vec_fetch_addrLen=log2Ceil(total_mats)+log2Ceil(arrayNums)+log2Ceil(wordlineNums)

    val total_arrays=total_mats*4

    val total_64b=total_mats*4*wordlineNums
    val core_addrLen=log2Ceil(arrayNums*wordlineNums)  // 64b len
}