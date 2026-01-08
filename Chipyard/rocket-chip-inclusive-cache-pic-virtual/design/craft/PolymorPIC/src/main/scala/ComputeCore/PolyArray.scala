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
import ModeInfo._

class PolyArray(kernal_configs:PolymorPIC_Kernal_Config) extends Module 
{

    val addrLen=kernal_configs.array_addrWidth
    val sram_rowNum=kernal_configs.wordlineNums
    val sram_colNum=kernal_configs.bitlineNums
    val io = IO(new Bundle {
        // dataIOs for normal cache data in out
        val cache_enable = Input(Bool())
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt(addrLen.W))
        val dataIn=Input(UInt(sram_colNum.W))
        val dataOut=Output(UInt(sram_colNum.W))

        // Mac out
        val mac_dataOut=Output(UInt(32.W))

        // mode selection
        val mode=Input(UInt(Mode_bitWidth.W))

        // left_vector input from mat's buffer
        val left_vector=Input(UInt((kernal_configs.vec_len).W))

        // Bias for left shift, form controller
        val left_shift_bias = Input(UInt(4.W))
        // If signed, from controller
        val signed = Input(Bool())

  })

    // Memory
    val mem=Module(new SRAM(word_num=kernal_configs.wordlineNums,
                        io_width=kernal_configs.bitlineNums))
    // MAC
    val mac=Module(new MAC(kernal_configs))
    
    mac.io.left_vec_in:=io.left_vector
    mac.io.right_vec_in:=mem.io.dataOut
    mac.io.bias:=io.left_shift_bias
    mac.io.signed:=io.signed

    mem.io.writeEn:=io.cache_write
    mem.io.en:=io.cache_enable
    mem.io.dataIn:=io.dataIn
    mem.io.row:=io.cache_addr
    io.dataOut:=mem.io.dataOut
    
    io.mac_dataOut:=Mux(isPICMode_Mac(io.mode),mac.io.dataOut,0.U)
}