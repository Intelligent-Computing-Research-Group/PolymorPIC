// Copyright (c) [Year] [name of copyright holder]
// [Software Name] is licensed under Mulan PSL v2.
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

class MAC(kernal_configs:PolymorPIC_Kernal_Config) extends Module 
{
    val vec_len=kernal_configs.bitlineNums

    val io = IO(new Bundle {
      val right_vec_in=Input(UInt(vec_len.W))
      val left_vec_in=Input(UInt(vec_len.W))
      val bias=Input(UInt(4.W))
      val signed=Input(Bool())
      val dataOut=Output(UInt(32.W))
    })

    // dontTouch(io)

    // AND
    var and_res=io.right_vec_in & io.left_vec_in

    // PopCount
    val reduced=PopCount(and_res)
      
    // Left shifter
    val Shifter=Module(new BarrelShifter)
    Shifter.io.bias:=io.bias
    Shifter.io.dataIn:=reduced
    
    io.dataOut:=Mux(io.signed,~(Shifter.io.dataOut)+1.U,Shifter.io.dataOut)
}