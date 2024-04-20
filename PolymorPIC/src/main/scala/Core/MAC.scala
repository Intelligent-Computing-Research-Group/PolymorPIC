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
    
    io.dataOut:=Mux((io.signed&&(io.bias(0)===1.U)),~(Shifter.io.dataOut),Shifter.io.dataOut)
}