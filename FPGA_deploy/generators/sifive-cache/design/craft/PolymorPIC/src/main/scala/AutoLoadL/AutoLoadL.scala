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

import chisel3._ // VecInit

import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._  // for getting subsystembus parameter
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.rocket._ // cache interface
import freechips.rocketchip.util._


class AutoLoadL(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit val p: Parameters) extends Module
{

    val io=IO(new Bundle {
        val request=Vec(sys_configs.totalMatNum, Flipped(Decoupled(new RequestAddress(core_configs))))
        val response=Vec(sys_configs.totalMatNum, Decoupled(Bool()))

        val dataReadFromBank=Input(UInt(64.W))
        val dataReadValid=Input(Bool())
        val accessArrayReq=Decoupled(new ReqPackage(sys_configs))

        val load_vec_addr=Output(UInt(6.W))
        val writeVecEn=Output(Bool())
        val vec_dataOut=Output(UInt(64.W))
    })

    io.accessArrayReq.valid:=false.B
    io.accessArrayReq.bits.addr:=0.U
    io.accessArrayReq.bits.dataWrittenToBank:=0.U
    io.accessArrayReq.bits.optype:=false.B

    val readEn_wire=WireInit(false.B)

    io.load_vec_addr:=0.U
    io.vec_dataOut:=0.U
    io.writeVecEn:=false.B

    val arbiter = Module(new RRArbiter(new RequestAddress(core_configs), sys_configs.totalMatNum))
    arbiter.io.out.ready:=false.B

    for(i<-0 until sys_configs.totalMatNum)
    {
        arbiter.io.in(i)<>io.request(i)
    }

    for(i<-0 until sys_configs.totalMatNum)
    {
        io.response(i).bits:=false.B
        io.response(i).valid:=false.B
    }

    val vec_buffer=RegInit(0.U(64.W))
    val counter=RegInit(0.U(3.W))

    val idle :: read_mem  :: write_L :: rev_vec :: report_finish ::Nil = Enum(5)
    val state=RegInit(idle)

    val req_mat_ID=RegInit(0.U(log2Ceil(sys_configs.totalMatNum).W))
    val vec_addr=RegInit(0.U((sys_configs.accessCacheFullAddrLen).W))
  
    // vec_buffer:=RegEnable(io.dataReadFromBank, RegNext(readEn_wire))

    switch(state)
    {
        is(idle)
        {
            arbiter.io.out.ready:=true.B
            // Receive a new request
            when(arbiter.io.out.fire)
            {
                req_mat_ID:=arbiter.io.chosen
                vec_addr:=arbiter.io.out.bits.subarrayID_rowID
                state:=read_mem
            }
        }
        is(read_mem)
        {
            io.accessArrayReq.valid:=true.B

            // Wait for req is accepted.
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.addr:=vec_addr
                io.accessArrayReq.bits.optype:=AccessArrayType.READ
                readEn_wire:=true.B
                state:=rev_vec
            }

        }
        is(rev_vec)
        {
            when(io.dataReadValid)
            {
                vec_buffer:=io.dataReadFromBank
                state:=write_L
            }
        }
        is(write_L)
        {
            io.writeVecEn:=true.B
            io.load_vec_addr:=req_mat_ID
            io.vec_dataOut:=vec_buffer
            state:=report_finish
        }
        is(report_finish)
        {
            for(i<- 0 until sys_configs.totalMatNum)
            {
                when(i.U===req_mat_ID)
                {io.response(i).valid:=true.B}

                when(io.response(i).fire)
                {
                    state:=idle
                }
            }
        }
    }
}