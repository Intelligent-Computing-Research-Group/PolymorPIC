package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._

import form_icenet._

class DMA_write_req(sysCfg:Sys_Config) extends Bundle {
    val nBytes=Input(UInt(sysCfg.maxAccessBytesSigLen.W))  // unit is byte
    val baseAddr_DRAM= Input(UInt(sysCfg.virtualAddrLen.W))
}

class DMA_write_page_split(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule
{
    val nXacts = 4      // DMA Channel number
    val outFlits = 32   // DMA internal buffer size
    val maxBytes = 64   // DMA maximum bytes per TileLink request
    val dmaWriter = LazyModule(new StreamWriter(nXacts, maxBytes)(p))
    val dmaWriter_node= dmaWriter.node

    
    lazy val module =new DMA_write_page_split_Impl
    class DMA_write_page_split_Impl extends LazyModuleImp(this)
    {
        val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
        val arrayAddrLen=log2Ceil(sysCfg.numBanks)+log2Ceil(sysCfg.matPerBank)+mat_inner_offset
        val dataBits=dmaWriter.module.dataBits
        val io = IO(new Bundle {
            // req from Store ctl
            val req_write=Flipped(Decoupled(new DMA_write_req(sysCfg)))
            val req_im2col_write=Flipped(Decoupled(new DMA_write_req(sysCfg)))
            val busy=Output(Bool())

            // tlb req
            val query_tlb_req=Decoupled(new TLB_req(sysCfg))
            val paddr_valid=Input(Bool())
            val paddr_received=Output(Bool())
            val paddr=Input(UInt(sysCfg.phyMemAddrLen.W))

            // Data from BankFetch
            val dataIn=Flipped(Decoupled(new StreamChannel(dataBits)))
        })
        // init
        io.paddr_received:=false.B
        io.dataIn.ready:=false.B

        val arbiter = Module(new RRArbiter(new DMA_write_req(sysCfg), 2))
        arbiter.io.in(0)<>io.req_write
        arbiter.io.in(1)<>io.req_im2col_write


        val nBytes = RegInit(0.U(sysCfg.maxAccessBytesSigLen.W))
        val cur_block_len_byte=RegInit(0.U(16.W))
        val baseAddr_DRAM = RegInit(0.U(sysCfg.virtualAddrLen.W)) 
        val p_page_id=RegInit(0.U(sysCfg.pageIDLen.W))
        val v_addr_ptr = RegInit(0.U(sysCfg.virtualAddrLen.W))
        val final_v_addr=baseAddr_DRAM+nBytes   // the final final_v_addr will not be accessed

        val main_idle :: pre_process  :: query_TLB ::query_wait_resp::bank_request::dma_request:: dma_writing::Nil = Enum(7)
        val main_state=RegInit(main_idle)
        arbiter.io.out.ready:= (main_state===main_idle)

        val busy = (main_state=/=main_idle)
        io.busy:= busy

        val enq_idle  :: read_array::en_queue::Nil = Enum(3)
        val enq_state=RegInit(enq_idle)

        // DMA read ram
        val dmaWriterIO = dmaWriter.module.io // icenet DMA interface
        val canRead = busy
        val canSendReq = RegInit(false.B)
        dmaWriterIO.req.valid := false.B
        dmaWriterIO.resp.ready :=  dmaWriterIO.resp.valid   // Completion signal

        // query TLB
        val vaddr_to_send=WireInit(0.U(64.W))
        io.query_tlb_req.valid:=(main_state===query_TLB)
        io.query_tlb_req.bits.vaddr:=vaddr_to_send

        // main state
        val next_page_begin_addr = ((v_addr_ptr>>12)+1.U)<<12
        val p_baseAddr_to_dma=(v_addr_ptr&"hFFF".U)+(p_page_id<<12)

        dmaWriterIO.resp.ready:=dmaWriterIO.resp.valid
        dmaWriterIO.req.bits.address := p_baseAddr_to_dma
        dmaWriterIO.req.bits.length := cur_block_len_byte

        dmaWriterIO.in.valid := false.B
        dmaWriterIO.in.bits.keep:=  io.dataIn.bits.keep
        dmaWriterIO.in.bits.last:=  io.dataIn.bits.last
        dmaWriterIO.in.bits.data:=  io.dataIn.bits.data


        switch(main_state)
        {
            is(main_idle)
            {
                when(arbiter.io.out.fire)
                {
                    val req_data=arbiter.io.out.bits
                    nBytes:=req_data.nBytes
                    baseAddr_DRAM:=req_data.baseAddr_DRAM
                    v_addr_ptr:=req_data.baseAddr_DRAM

                    main_state:=pre_process
                    enq_state:=read_array
                }
            }
            is(pre_process)
            {
                when(v_addr_ptr<final_v_addr)
                {
                    main_state   :=  query_TLB
                    cur_block_len_byte   :=  Mux(final_v_addr>next_page_begin_addr,next_page_begin_addr-v_addr_ptr,final_v_addr-v_addr_ptr)
                }
                .otherwise
                {
                    main_state:=main_idle // finish
                }
            }
            is(query_TLB)
            {
                when(io.query_tlb_req.fire)
                {
                    vaddr_to_send:=v_addr_ptr
                    main_state:=query_wait_resp
                }
            }
            is(query_wait_resp)
            {
                when(io.paddr_valid)
                {
                    p_page_id:=io.paddr>>12
                    io.paddr_received:=true.B
                    canSendReq:=true.B
                    main_state:=dma_request
                }
            }
            // Req dma data
            is(dma_request)
            {
                val p_baseAddr_to_dma=(v_addr_ptr&"hFFF".U)+(p_page_id<<12)
                dmaWriterIO.req.valid:=true.B
                when(dmaWriterIO.req.fire)
                {
                    canSendReq := false.B
                    main_state:=dma_writing
                }
            }
            is(dma_writing)
            {
                dmaWriterIO.in.valid :=  io.dataIn.valid
                io.dataIn.ready:=dmaWriterIO.in.ready
                dmaWriterIO.in.bits.data:=io.dataIn.bits.data

                // Finish
                when(dmaWriterIO.resp.fire)
                {           
                    v_addr_ptr:=next_page_begin_addr
                    main_state := pre_process
                }
            }
        }
    }
}