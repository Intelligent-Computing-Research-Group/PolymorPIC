package PIC

import chisel3._
import chisel3.util._


class BarrelShifter extends Module
{
    val inWidth=8
    val outWidth=32
    val io = IO(new Bundle
    {
        val dataIn = Input(UInt(inWidth.W))
        val bias=Input(UInt(4.W))
        val dataOut = Output(UInt(outWidth.W))
    })

    val stage0 = io.dataIn
    val stage1 = Mux(io.bias(0), Cat(stage0, 0.U(1.W)), stage0)
    val stage2 = Mux(io.bias(1), Cat(stage1, 0.U(2.W)), stage1)
    val stage3 = Mux(io.bias(2), Cat(stage2, 0.U(4.W)), stage2)
    val stage4 = Mux(io.bias(3), Cat(stage3, 0.U(8.W)), stage3)

    io.dataOut:=stage4
}