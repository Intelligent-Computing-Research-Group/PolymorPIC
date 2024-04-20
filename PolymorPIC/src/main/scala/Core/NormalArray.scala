package PIC

import chisel3._
import chisel3.util._
import ModeInfo._

class NormalArray(kernal_configs:PolymorPIC_Kernal_Config) extends Module 
{

    val word_num=kernal_configs.arrayNums*kernal_configs.wordlineNums
    val bitline_num=kernal_configs.bitlineNums

    val io = IO(new Bundle {
        // dataIOs for normal cache data in out
        val cache_enable = Input(Bool())
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt(log2Ceil(word_num).W))
        val dataIn=Input(UInt(bitline_num.W))
        val dataOut=Output(UInt(bitline_num.W))
  })

    // Memory
    val mem=Module(new SRAM(word_num=word_num,
                    io_width=kernal_configs.bitlineNums))
    
    mem.io.writeEn:=io.cache_write
    mem.io.en:=io.cache_enable
    mem.io.dataIn:=io.dataIn
    mem.io.row:=io.cache_addr
    io.dataOut:=mem.io.dataOut
    
    // io.mac_dataOut:=Mux(isPICMode_Mac(io.mode),mac.io.dataOut,0.U)
}