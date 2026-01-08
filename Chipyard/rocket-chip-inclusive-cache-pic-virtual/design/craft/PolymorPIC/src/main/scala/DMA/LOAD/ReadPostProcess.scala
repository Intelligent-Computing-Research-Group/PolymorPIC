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
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}

import form_icenet._

object DataDir
{
    val bank=0.U
    val p2sLbuffer=1.U
    val p2sRbuffer=2.U
    val p2sRTbuffer=3.U
}

class ReadPostProcess_req(sysCfg:Sys_Config) extends Bundle
{
    // Bank address
    val bank_baseAddr=UInt(sysCfg.accessCacheFullAddrLen.W)
    val bank_endAddr=UInt(sysCfg.accessCacheFullAddrLen.W)
    // If need padding and pad info
    val padding=Bool()
    val padInfo=new PadInfo(sysCfg)
    // For p2s, data is given to buffer instead of bank
    val dataDir=UInt(2.W)
}

class ReadPostProcess(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{

    val io = IO(new Bundle {
        // Bank Adress and pad info
        val req=Flipped(Decoupled(new ReadPostProcess_req(sysCfg)))

        // data from page split
        val unaligned_dataIn=Flipped(Decoupled(new StreamChannel(64)))

        // access bank
        val accessBank=Decoupled(new ReqPackage(sysCfg))

        // access buffer
        val dataToBufAvailableP2SL=Decoupled(Bool())
        val dataToBufAvailableP2SR=Decoupled(Bool())
        val dataToBufAvailableP2SRT=Decoupled(Bool())
        val dataToBuf=Output(UInt(sysCfg.busWidth.W))

        // busy
        val busy=Output(Bool())
    })

    val aligner = Module(new Aligner(sysCfg.busWidth))

    val padUnit=Module(new PaddingUnit(sysCfg))
    padUnit.io.streamIn<>io.unaligned_dataIn
    aligner.io.in<>padUnit.io.streamOut

    val baseAddr_Array_reg=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val endAddr_Array_reg=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val padInfo_reg=Reg(new PadInfo(sysCfg))
    val dataDir_reg=RegInit(0.U(2.W))

    val sIdle :: setPad :: wait_finish ::Nil = Enum(3)
    val state=RegInit(sIdle)
    io.req.ready:=(state===sIdle)
    padUnit.io.pad_req.bits:=padInfo_reg
    padUnit.io.pad_req.valid:=(state===setPad)

    io.dataToBufAvailableP2SL.bits:=DontCare
    io.dataToBufAvailableP2SR.bits:=DontCare
    io.dataToBufAvailableP2SRT.bits:=DontCare

    switch(state)
    {
        is(sIdle)
        {
            when(io.req.fire)
            {
                val reqInfo=io.req.bits
                padInfo_reg:=Mux(reqInfo.padding,reqInfo.padInfo,padInfo_reg)
                baseAddr_Array_reg:=reqInfo.bank_baseAddr
                endAddr_Array_reg:=reqInfo.bank_endAddr
                dataDir_reg:=reqInfo.dataDir
                state:=Mux(reqInfo.padding&reqInfo.padInfo.padSize>0.U,setPad,wait_finish)
            }
        }
        is(setPad)
        {
            when(padUnit.io.pad_req.fire)
            {
                state:=wait_finish
            }
        }
        is(wait_finish)
        {
            val dataDirBankFinish=(baseAddr_Array_reg===endAddr_Array_reg)&(io.accessBank.fire)
            val dataDirBufFinish=(!io.dataToBufAvailableP2SL.ready)&(!io.dataToBufAvailableP2SR.ready)&(!io.dataToBufAvailableP2SRT.ready)

            state:=Mux(dataDir_reg===DataDir.bank,Mux(dataDirBankFinish,sIdle,state),
                        Mux(dataDirBufFinish,sIdle,state))
        }
    }

    io.busy:= !(state===sIdle)

   // Write array part: Data connection
    io.accessBank.bits.addr:=baseAddr_Array_reg
    io.accessBank.bits.optype:=AccessArrayType.WRITE
    io.accessBank.bits.dataWrittenToBank:=aligner.io.out.bits.data
    when(io.accessBank.fire&aligner.io.out.fire)
    {baseAddr_Array_reg:=baseAddr_Array_reg+1.U}

    // Write buffer part: Data connection
    io.dataToBuf:=aligner.io.out.bits.data

    // Data valid signal connection
    io.accessBank.valid:=Mux(dataDir_reg===DataDir.bank,aligner.io.out.valid,false.B)
    io.dataToBufAvailableP2SL.valid:=Mux(dataDir_reg===DataDir.p2sLbuffer,aligner.io.out.valid,false.B)
    io.dataToBufAvailableP2SR.valid:=Mux(dataDir_reg===DataDir.p2sRbuffer,aligner.io.out.valid,false.B)
    io.dataToBufAvailableP2SRT.valid:=Mux(dataDir_reg===DataDir.p2sRTbuffer,aligner.io.out.valid,false.B)
    aligner.io.out.ready:=MuxCase(false.B,Seq(
        (dataDir_reg===DataDir.bank) -> io.accessBank.ready,
        (dataDir_reg===DataDir.p2sLbuffer) -> io.dataToBufAvailableP2SL.ready,
        (dataDir_reg===DataDir.p2sRbuffer) -> io.dataToBufAvailableP2SR.ready,
        (dataDir_reg===DataDir.p2sRTbuffer) -> io.dataToBufAvailableP2SRT.ready
    ))

  
    // assert((io.accessBank.fire&&aligner.io.out.fire)||((!io.accessBank.fire)&(!aligner.io.out.fire)),"Something terrible happens!")
}