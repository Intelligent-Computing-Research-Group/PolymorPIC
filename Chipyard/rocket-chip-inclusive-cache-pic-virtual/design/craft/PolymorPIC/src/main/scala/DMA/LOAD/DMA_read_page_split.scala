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
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._

import form_icenet._

class DMA_read_req(sysCfg:Sys_Config) extends Bundle {
    val nByte=UInt((sysCfg.maxAccessBytesSigLen).W)
    val baseAddr_DRAM= UInt(sysCfg.virtualAddrLen.W)
}


class DMA_read_page_split(sysCfg:Sys_Config)(implicit p: Parameters) extends LazyModule
{
    val nXacts = 4      // DMA channels num
    val outFlits = 32   // DMA inside buffer size
    val maxBytes = 64   // DMA each TileLink request max bytes
    val dmaReader = LazyModule(new StreamReader(nXacts, outFlits, maxBytes)(p))
    val dmaReader_node= dmaReader.node

    lazy val module =new DMA_read_page_split_Impl
    class DMA_read_page_split_Impl extends LazyModuleImp(this)
    {
        val pageSizeBytes=sysCfg.pageSizeBytes
        val dataBits=dmaReader.core.module.dataBits
        val io = IO(new Bundle {
            // val req_read=Flipped(Decoupled(new DMA_read_req(sysCfg)))
            val req=Flipped(Decoupled(new DMA_read_req(sysCfg)))
            val busy=Output(Bool())

            // Tlb
            val query_tlb_req=Decoupled(new TLB_req(sysCfg))
            val paddr_valid=Input(Bool())
            val paddr_received=Output(Bool())
            val paddr=Input(UInt(sysCfg.phyMemAddrLen.W))

            // last row? Used to mark whether it is the global last one. The lastRow signals of each master are OR ed.
            val lastRow=Input(Bool())

            // return data
            val dataOut=Decoupled(new StreamChannel(w=dataBits))
        })

        // init
        io.paddr_received:=false.B

        val arbiter = Module(new RRArbiter(new DMA_read_req(sysCfg), 1))
        arbiter.io.in(0)<>io.req


        val nByte = RegInit(0.U((sysCfg.maxAccessBytesSigLen).W))
        val cur_block_len=RegInit(0.U(log2Ceil(pageSizeBytes+1).W))        // bytes
        val baseAddr_DRAM = RegInit(0.U(sysCfg.virtualAddrLen.W)) 
        val p_page_id=RegInit(0.U(sysCfg.pageIDLen.W))
        val p_page_id_valid=RegInit(false.B)
        val v_page_valid_id=RegInit(0.U(sysCfg.pageIDLen.W))
        val v_addr_ptr = RegInit(0.U(sysCfg.virtualAddrLen.W))
        val can_skip_ptw= p_page_id_valid&((v_addr_ptr>>log2Ceil(pageSizeBytes))===v_page_valid_id)
        val write_array_addr_ptr=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
        val final_v_addr=baseAddr_DRAM+nByte   // the final final_v_addr will not be accessed


        val main_idle :: pre_process  :: query_TLB ::query_wait_resp::dma_request:: dma_reading::dma_finish::Nil = Enum(7)
        val main_state=RegInit(main_idle)
        val busy = (main_state=/=main_idle)
        io.busy:= busy
        arbiter.io.out.ready:= false.B

        // DMA read ram
        val dmaReaderIO = dmaReader.module.io // icenet DMA proveded mem read port
        val canRead = busy
        val canSendReq = RegInit(false.B)
        dmaReaderIO.req.valid := false.B
        dmaReaderIO.out.ready :=false.B
        // dmaReaderIO.resp channel is not used here
        dmaReaderIO.req.bits:=DontCare
        dmaReaderIO.resp.ready := dmaReaderIO.resp.valid

        // query TLB
        val mmio_addr=MMIO.query_tlb_MMIO
        io.query_tlb_req.valid:= (main_state===query_TLB)
        io.query_tlb_req.bits.vaddr:=v_addr_ptr

        // Put the readed data into the queue
        val data_queue=Module(new Queue(new StreamChannel(w=dataBits), 6))
        data_queue.io.enq.valid:=dmaReaderIO.out.valid
        dmaReaderIO.out.ready := data_queue.io.enq.ready
        data_queue.io.enq.bits.data:=dmaReaderIO.out.bits.data
        data_queue.io.enq.bits.keep:=dmaReaderIO.out.bits.keep
        // queue state
        val is_queue_empty = !data_queue.io.deq.valid
        val is_queue_has_val = data_queue.io.deq.valid

        io.dataOut<>data_queue.io.deq

        // Main run state
        val next_page_begin_addr = ((v_addr_ptr>>log2Ceil(pageSizeBytes))+1.U)<<log2Ceil(pageSizeBytes)
        // Global last needs to satisfy the last row and the last byte of the last row
        data_queue.io.enq.bits.last:= dmaReaderIO.out.bits.last&io.lastRow&(next_page_begin_addr>=final_v_addr)
        switch(main_state)
        {
            is(main_idle)
            {
                arbiter.io.out.ready:= true.B
                write_array_addr_ptr:=0.U
                when(arbiter.io.out.fire)
                {
                    val req_data=arbiter.io.out.bits
                    nByte:=req_data.nByte
                    baseAddr_DRAM:=req_data.baseAddr_DRAM
                    v_addr_ptr:=req_data.baseAddr_DRAM

                    main_state:=pre_process
                }
            }
            is(pre_process)
            {
                when(v_addr_ptr<final_v_addr)
                {
                    // main_state   :=  Mux(can_skip_ptw,dma_request,query_TLB)
                    main_state   :=  query_TLB
                    cur_block_len   :=  Mux(final_v_addr>next_page_begin_addr,next_page_begin_addr-v_addr_ptr,final_v_addr-v_addr_ptr)
                }
                .otherwise
                {
                    main_state:=main_idle // Finish
                }
            }
            is(query_TLB)
            {
                when(io.query_tlb_req.fire)
                {   main_state:=query_wait_resp }
            }
            is(query_wait_resp)
            {
                when(io.paddr_valid)
                {
                    p_page_id:=io.paddr>>log2Ceil(pageSizeBytes)
                    p_page_id_valid:=true.B
                    v_page_valid_id:=v_addr_ptr>>log2Ceil(pageSizeBytes)
                    io.paddr_received:=true.B
                    canSendReq:=true.B
                    main_state:=dma_request
                }
            }
            is(dma_request)
            {
                val p_baseAddr_to_dma=(v_addr_ptr&"hFFF".U)+(p_page_id<<12)
                dmaReaderIO.req.valid:=true.B
                when(dmaReaderIO.req.fire)
                {
                    canSendReq := false.B
                    dmaReaderIO.req.bits.address := p_baseAddr_to_dma
                    dmaReaderIO.req.bits.length := cur_block_len
                    dmaReaderIO.req.bits.partial := false.B
                    main_state:=dma_finish
                }
            }
            is(dma_reading)
            {
                // data_queue.io.enq.bits := dmaReaderIO.out.bits
                val is_last=dmaReaderIO.out.bits.last
                dmaReaderIO.out.ready := data_queue.io.enq.ready
                // When fire, data_queue.io.enq.ready must be true
                when(dmaReaderIO.out.fire)
                {
                    data_queue.io.enq.valid:=true.B
                    main_state:=Mux(is_last,dma_finish,main_state)
                }   
            }
            is(dma_finish)
            {
                val is_last=dmaReaderIO.out.bits.last
                when(dmaReaderIO.out.fire&is_last)
                {  
                    v_addr_ptr:=next_page_begin_addr
                    main_state:=pre_process
                } 
            }
        }

    }
}