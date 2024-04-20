package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._

class DMA_read_req(sys_configs:Sys_Config) extends Bundle {
    val len=Input(UInt((sys_configs.dma_len_sigLen).W))  //单位为一个64b
    val baseAddr_DRAM= Input(UInt(32.W))
    val baseAddr_Array= Input(UInt((sys_configs.accessBankFullAddr_sigLen).W))
    val dma_read_type=Input(UInt(2.W))
}

class DMA_reader_Ctl(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule
{
    val nXacts = 4      // DMA 通道数
    val outFlits = 32   // DMA 内部 buffer 大小
    val maxBytes = 64   // DMA 每个 TileLink 请求最大字节数
    val dmaReader = LazyModule(new StreamReader(nXacts, outFlits, maxBytes)(p))
    val dmaReader_node= dmaReader.node

    lazy val module =new DMA_reader_Ctl_Impl
    class DMA_reader_Ctl_Impl extends LazyModuleImp(this)
    {
        val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
        val arrayAddrLen=log2Ceil(sys_configs.bank_num)+log2Ceil(sys_configs.mat_per_bank)+mat_inner_offset
        val io = IO(new Bundle {
            // val req_read=Flipped(Decoupled(new DMA_read_req(sys_configs)))
            val req_load_P=Flipped(Decoupled(new DMA_read_req(sys_configs)))
            val req_p2s_R=Flipped(Decoupled(new DMA_read_req(sys_configs)))
            val req_p2s_L=Flipped(Decoupled(new DMA_read_req(sys_configs)))
            val busy=Output(Bool())

            // Tlb
            val query_tlb_req=Decoupled(new TLB_req)
            val paddr_valid=Input(Bool())
            val paddr_received=Output(Bool())
            val paddr=Input(UInt(32.W))

            // Data access: array
            val accessArray=Decoupled(new ReqPackage(sys_configs))
            // Data access: p2s
            val write_p2s_L=Output(Bool())
            val write_p2s_R=Output(Bool())
            val dataTo_p2s=Output(UInt(64.W))
        })

        io.write_p2s_L:=false.B
        io.write_p2s_R:=false.B
        io.dataTo_p2s:=0.U
        io.paddr_received:=false.B

        val arbiter = Module(new RRArbiter(new DMA_read_req(sys_configs), 3))
        arbiter.io.in(0)<>io.req_load_P
        arbiter.io.in(1)<>io.req_p2s_R
        arbiter.io.in(2)<>io.req_p2s_L

        val dataBits=dmaReader.core.module.dataBits

        val len_64b = RegInit(0.U((sys_configs.dma_len_sigLen).W))
        val cur_64b_len=RegInit(0.U(10.W))
        val cur_block_len=RegInit(0.U(13.W))
        val baseAddr_DRAM = RegInit(0.U(32.W)) 
        val baseAddr_Array = RegInit(0.U(arrayAddrLen.W)) 
        val total_nbytes = len_64b <<  log2Ceil(dataBits/8)
        val p_page_id=RegInit(0.U(32.W))
        val v_addr_ptr = RegInit(0.U(32.W))
        val write_array_addr_ptr=RegInit(0.U(16.W))
        val read_ptr = RegInit(0.U(12.W))    
        val final_v_addr=baseAddr_DRAM+total_nbytes   // the final final_v_addr will not be accessed
        val dma_read_type=RegInit(0.U(2.W))       // from p2s or normal access


        val main_idle :: pre_process  :: query_TLB ::query_wait_resp::dma_request:: dma_reading::dma_finish::Nil = Enum(7)
        val main_state=RegInit(main_idle)
        val busy = (main_state=/=main_idle)
        io.busy:= busy
        arbiter.io.out.ready:= false.B

        // DMA 读ram
        val dmaReaderIO = dmaReader.module.io // icenet DMA 提供的读内存接口
        val canRead = busy
        val canSendReq = RegInit(false.B)
        dmaReaderIO.req.valid := false.B
        dmaReaderIO.out.ready :=false.B
        // init
        dmaReaderIO.req.bits:=DontCare
        dmaReaderIO.resp.ready := dmaReaderIO.resp.valid



        // 查询TLB
        val vaddr_to_send=WireInit(0.U(64.W))
        val mmio_addr=sys_configs.query_tlb_MMIO
        io.query_tlb_req.valid:= (main_state===query_TLB)
        io.query_tlb_req.bits.vaddr:=vaddr_to_send

        // 读出来的数据放入queue
        val data_queue=Module(new Queue(UInt(64.W), 6))
        data_queue.io.enq.valid:=false.B
        data_queue.io.enq.bits:=0.U
        // queue state
        val is_queue_empty = !data_queue.io.deq.valid
        val is_queue_has_val = data_queue.io.deq.valid

        // 运行主干状态
        val next_page_begin_addr = ((v_addr_ptr>>12)+1.U)<<12
        switch(main_state)
        {
            is(main_idle)
            {
                arbiter.io.out.ready:= true.B
                write_array_addr_ptr:=0.U
                when(arbiter.io.out.fire)
                {
                    val req_data=arbiter.io.out.bits
                    len_64b:=req_data.len
                    baseAddr_DRAM:=req_data.baseAddr_DRAM
                    baseAddr_Array:=req_data.baseAddr_Array
                    v_addr_ptr:=req_data.baseAddr_DRAM
                    dma_read_type:=req_data.dma_read_type

                    main_state:=pre_process
                }
            }
            is(pre_process)
            {
                when(v_addr_ptr<final_v_addr)
                {
                    main_state   :=  query_TLB
                    cur_block_len   :=  Mux(final_v_addr>next_page_begin_addr,next_page_begin_addr-v_addr_ptr,final_v_addr-v_addr_ptr)
                }
                .otherwise
                {
                    main_state:=main_idle // 结束
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
                    main_state:=dma_reading
                }
            }
            is(dma_reading)
            {
                val cur_64b_len= cur_block_len>>3

                data_queue.io.enq.bits := dmaReaderIO.out.bits.data
                dmaReaderIO.out.ready := (read_ptr < cur_64b_len) && data_queue.io.enq.ready
                // fire的同时一定会有data_queue.io.enq.ready为真
                when(dmaReaderIO.out.fire)
                {  
                    read_ptr := read_ptr + 1.U
                    data_queue.io.enq.valid:=true.B
                }   

                // dmaReaderIO.resp.ready := dmaReaderIO.resp.valid        
                when(read_ptr===cur_64b_len){main_state:=dma_finish}
            }
            is(dma_finish)
            {
                // 先等queue中数据被取光
                when(is_queue_empty)
                {
                    v_addr_ptr:=next_page_begin_addr
                    main_state:=pre_process
                    read_ptr:=0.U
                }
            }
            
        }

        // 从queue中取出数据到array或者p2s
        val deq_idle :: write_p2s  :: write_array ::Nil = Enum(3)
        val deq_queue_state=RegInit(deq_idle)
        data_queue.io.deq.ready:=false.B
        io.accessArray.valid:=false.B
        io.accessArray.bits.optype:=AccessArrayType.WRITE
        io.accessArray.bits.dataWrittenToBank:=0.U
        io.accessArray.bits.addr:=0.U
        switch(deq_queue_state)
        {
            is(deq_idle)
            {
                // 队里有东西
                when(data_queue.io.deq.valid)
                {
                    deq_queue_state:=Mux(dma_read_type===DMA_read_type.NORMAL,write_array,write_p2s)
                }
            }
            is(write_array)
            {
                
                io.accessArray.valid:=is_queue_has_val

                when(io.accessArray.fire)
                {
                    // deq
                    data_queue.io.deq.ready:=true.B
                    // queue head val
                    val data=data_queue.io.deq.bits
                    write_array_addr_ptr:=write_array_addr_ptr+1.U
                    io.accessArray.bits.addr:=baseAddr_Array+write_array_addr_ptr
                    io.accessArray.bits.optype:=AccessArrayType.WRITE
                    io.accessArray.bits.dataWrittenToBank:=data
                }

                when(is_queue_empty)
                {
                    deq_queue_state:=deq_idle
                }

                
            }
            is(write_p2s)
            {
                data_queue.io.deq.ready:=true.B
                when(data_queue.io.deq.fire)
                {
                    io.write_p2s_L:= (dma_read_type===DMA_read_type.DMA_P2S_L)
                    io.write_p2s_R:= (dma_read_type===DMA_read_type.DMA_P2S_R)
                    io.dataTo_p2s:=data_queue.io.deq.bits
                }

                when(is_queue_empty)
                {
                    deq_queue_state:=deq_idle
                }
            }
        }






    }




}