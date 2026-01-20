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
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

//  +-+-+-+ +-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |T|h|e| |m|o|d|u|l|e| |r|e|s|i|d|e|s|
//  +-+-+-+-+-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |L|2| |s|i|d|e|                      
//  +-+-+ +-+-+-+-+  

class TLB_req(sysCfg:Sys_Config) extends Bundle {
  val vaddr = UInt(sysCfg.virtualAddrLen.W)
}


// The module resides L2 side
// The module is responsible for receive command from DMA and query the rocc side's TLB
class TLB_req_schedular(sysCfg:Sys_Config)(implicit p: Parameters) extends LazyModule
{
    val tl_master_tlb_req_node=TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
            name = "tl_master_tlb_req", sourceId = IdRange(0, 1))))))
    
    lazy val module =new TLB_req_schedular_impl
    class TLB_req_schedular_impl extends LazyModuleImp(this)
    {
        val io = IO(new Bundle {
            val tlb_req=Vec(2, Flipped(Decoupled(new TLB_req(sysCfg))))
            val paddr=Output(UInt(sysCfg.phyMemAddrLen.W))
            val paddr_valid=Output(Bool())
            val paddr_received=Vec(2,Input(Bool()))
        })

        io.paddr_valid:=false.B


        val arbiter = Module(new RRArbiter(new TLB_req(sysCfg), 2))
        for(i<-0 until 2)
        {   arbiter.io.in(i)<>io.tlb_req(i) }

        arbiter.io.out.ready:=false.B

        val sche_idle :: send_req_to_tile :: wait_result  :: return_paddr ::Nil = Enum(4)
        val sche_state=RegInit(sche_idle)

        val vaddr_reg=RegInit(0.U(sysCfg.virtualAddrLen.W))
        val paddr_reg=RegInit(0.U(sysCfg.phyMemAddrLen.W))
        // CUrrently disable it
        if(sysCfg.vituralization)
        {io.paddr:=paddr_reg}
        else
        {io.paddr:=vaddr_reg}
        // tilelink
        val (tlb_query_tl, tlb_query_edge) = tl_master_tlb_req_node.out(0)
        val mm_addr=MMIO.query_tlb_MMIO
        println("TLB sender addr = ",mm_addr)
        tlb_query_tl.a.valid:=(sche_state===send_req_to_tile)
        tlb_query_tl.a.bits := tlb_query_edge.Put(
            fromSource = 0.U,
            toAddress = mm_addr,
            lgSize = 3.U,
            data = vaddr_reg
        )._2
        tlb_query_tl.d.ready:= (sche_state===wait_result)

        switch(sche_state)
        {
            is(sche_idle)
            {
                arbiter.io.out.ready:=true.B
                when(arbiter.io.out.fire)
                {
                    vaddr_reg:=arbiter.io.out.bits.vaddr
                    if(sysCfg.vituralization)
                    {sche_state:=send_req_to_tile}
                    else{sche_state:=return_paddr}
                }
            }
            is(send_req_to_tile)
            {
                when(tlb_query_tl.a.fire)
                {sche_state:=wait_result}
            }
            is(wait_result)
            {
                when(tlb_query_tl.d.fire)
                {
                    paddr_reg:= (tlb_query_tl.d.bits.data)
                    sche_state:=return_paddr
                }
            }
            is(return_paddr)
            {
                io.paddr_valid:=true.B
                sche_state:=Mux(io.paddr_received.reduce(_ | _),sche_idle,sche_state)
            }
        }

    }

}