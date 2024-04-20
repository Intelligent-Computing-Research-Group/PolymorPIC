package PIC

import chisel3._
import chisel3.util._

// class SRAM(kernal_configs:PolymorPIC_Kernal_Config) extends Module 
// {
//     val rowNum=kernal_configs.wordlineNums
//     val colNum=kernal_configs.bitlineNums
//     val ioWidth=colNum
//     val addrLen=kernal_configs.array_addrWidth

//     val io = IO(new Bundle {
//         val row = Input(UInt(addrLen.W))
//         val writeEn = Input(Bool())
//         val en = Input(Bool())
//         val dataIn = Input(UInt(ioWidth.W))
//         val dataOut = Output(UInt(ioWidth.W))
//     })
    
//     val mem = SyncReadMem(rowNum,UInt(colNum.W))
//     // single port 
//     when(io.writeEn & io.en){
//         mem.write(io.row,io.dataIn)
//     }
    
//     io.dataOut := mem.read(io.row, io.en&&(!io.writeEn))

// }


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