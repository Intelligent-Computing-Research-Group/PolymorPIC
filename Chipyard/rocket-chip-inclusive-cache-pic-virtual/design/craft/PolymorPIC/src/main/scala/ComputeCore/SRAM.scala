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

class SRAM(word_num:Int,io_width:Int) extends Module 
{

    val io = IO(new Bundle {
        val row = Input(UInt(log2Ceil(word_num).W))
        val writeEn = Input(Bool())
        val en = Input(Bool())
        val dataIn = Input(UInt(io_width.W))
        val dataOut = Output(UInt(io_width.W))
    })
    
    val mem = SyncReadMem(word_num,UInt(io_width.W))
    // single port 
    when(io.writeEn & io.en){
        mem.write(io.row,io.dataIn)
    }
    
    io.dataOut := mem.read(io.row, io.en&&(!io.writeEn))

}

class SRAM8(word_num:Int,io_width:Int) extends Module 
{

    val io = IO(new Bundle {
        val addr = Input(UInt(log2Ceil(word_num).W))
        val writeEn = Input(Bool())
        val en = Input(Bool())
        val dataIn = Input(UInt(io_width.W))
        val dataOut = Output(UInt(io_width.W))
    })
    
    val mem = SyncReadMem(word_num,UInt(io_width.W))
    // single port 
    when(io.writeEn & io.en){
        mem.write(io.addr,io.dataIn)
    }
    
    io.dataOut := mem.read(io.addr, io.en&&(!io.writeEn))

}