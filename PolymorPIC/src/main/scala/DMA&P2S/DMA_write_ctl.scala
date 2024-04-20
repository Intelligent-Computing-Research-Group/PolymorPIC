package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._

class DMA_write_req(sys_configs:Sys_Config) extends Bundle {
    val len=Input(UInt((sys_configs.dma_len_sigLen).W))  //单位为一个64b
    val baseAddr_DRAM= Input(UInt(32.W))
    val baseAddr_Array= Input(UInt((sys_configs.accessBankFullAddr_sigLen).W))
}

class DMA_writer_Ctl(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule
{
    val nXacts = 4      // DMA 通道数
    val outFlits = 32   // DMA 内部 buffer 大小
    val maxBytes = 64   // DMA 每个 TileLink 请求最大字节数
    val dmaWriter = LazyModule(new StreamWriter(nXacts, maxBytes)(p))
    val dmaWriter_node= dmaWriter.node

    
    lazy val module =new DMA_writer_Ctl_Impl
    class DMA_writer_Ctl_Impl extends LazyModuleImp(this)
    {
        val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
        val arrayAddrLen=log2Ceil(sys_configs.bank_num)+log2Ceil(sys_configs.mat_per_bank)+mat_inner_offset
        val io = IO(new Bundle {
            val req_write=Flipped(Decoupled(new DMA_write_req(sys_configs)))
            val busy=Output(Bool())

            // tlb req
            val query_tlb_req=Decoupled(new TLB_req)
            val paddr_valid=Input(Bool())
            val paddr_received=Output(Bool())
            val paddr=Input(UInt(32.W))

            // Data access: array
            val accessArray=Decoupled(new ReqPackage(sys_configs))
            val dataReadFromArray=Input(UInt(64.W))
        })
        // init
        io.req_write.bits:= DontCare
        io.req_write.ready:= false.B
        io.paddr_received:=false.B
        io.accessArray.bits:=DontCare

        val dataBits=dmaWriter.module.dataBits

        val len_64b = RegInit(0.U(sys_configs.dma_len_sigLen.W))
        val cur_block_len_byte=RegInit(0.U(16.W))
        val baseAddr_DRAM = RegInit(0.U(32.W)) 
        val baseAddr_Array = RegInit(0.U(arrayAddrLen.W)) 
        val total_nbytes = len_64b <<  log2Ceil(dataBits/8)
        val p_page_id=RegInit(0.U(32.W))
        val v_addr_ptr = RegInit(0.U(32.W))
        val array_ptr_for_accessArray=RegInit(0.U(16.W))
        val array_ptr_for_enq=RegInit(0.U(16.W))
        val cur_block_ptr_64b = RegInit(0.U(12.W))    
        val final_v_addr=baseAddr_DRAM+total_nbytes   // the final final_v_addr will not be accessed

        val main_idle :: pre_process  :: query_TLB ::query_wait_resp::dma_request:: dma_writing::dma_finish::Nil = Enum(7)
        val main_state=RegInit(main_idle)

        val busy = (main_state=/=main_idle)
        io.busy:= busy

        val enq_idle  :: read_array::en_queue::Nil = Enum(3)
        val enq_state=RegInit(enq_idle)

        // DMA 读ram
        val dmaWriterIO = dmaWriter.module.io // icenet DMA 提供的读内存接口
        val canRead = busy
        val canSendReq = RegInit(false.B)
        dmaWriterIO.req.valid := false.B
        dmaWriterIO.resp.ready :=  dmaWriterIO.resp.valid   // 完成信号

        when(io.req_write.fire)
        {
            val req_data=io.req_write.bits
            len_64b:=req_data.len
            baseAddr_DRAM:=req_data.baseAddr_DRAM
            baseAddr_Array:=req_data.baseAddr_Array
            v_addr_ptr:=req_data.baseAddr_DRAM

            main_state:=pre_process
            enq_state:=read_array
        }

        // 查询TLB
        val vaddr_to_send=WireInit(0.U(64.W))
        io.query_tlb_req.valid:=(main_state===query_TLB)
        io.query_tlb_req.bits.vaddr:=vaddr_to_send

        // 读出来的数据放入queue
        val data_queue=Module(new Queue(UInt(64.W), 8))
        data_queue.io.enq.bits:=0.U
        data_queue.io.deq.ready:=false.B
        data_queue.io.enq.valid:=false.B

        val dataDeq_reg=RegInit(0.U(64.W))
        val is_queue_empty = !data_queue.io.deq.valid
        val is_queue_has_val = data_queue.io.deq.valid
        val is_queue_has_space= data_queue.io.enq.ready

        // 运行主干状态
        val next_page_begin_addr = ((v_addr_ptr>>12)+1.U)<<12
        val p_baseAddr_to_dma=(v_addr_ptr&"hFFF".U)+(p_page_id<<12)
        dmaWriterIO.resp.ready:=dmaWriterIO.resp.valid
        dmaWriterIO.req.bits.address := p_baseAddr_to_dma
        dmaWriterIO.req.bits.length := cur_block_len_byte
        dmaWriterIO.req.bits.length := cur_block_len_byte
        dmaWriterIO.req.bits.length := cur_block_len_byte
        dmaWriterIO.in.valid := false.B
        dmaWriterIO.in.bits.keep:=  DontCare
        dmaWriterIO.in.bits.last:=  DontCare
        dmaWriterIO.in.bits.data:=data_queue.io.deq.bits
        switch(main_state)
        {
            is(main_idle)
            {
                io.req_write.ready:=true.B
                when(io.req_write.fire)
                {
                    val req_data=io.req_write.bits
                    len_64b:=req_data.len
                    baseAddr_DRAM:=req_data.baseAddr_DRAM
                    baseAddr_Array:=req_data.baseAddr_Array
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
                dmaWriterIO.req.valid:=true.B
                when(dmaWriterIO.req.fire)
                {
                    canSendReq := false.B
                    // dmaWriterIO.req.bits.address := p_baseAddr_to_dma
                    // dmaWriterIO.req.bits.length := cur_block_len_byte
                    main_state:=dma_writing
                }
            }
            is(dma_writing)
            {
                // 队列有数且dmaWriter可接收，就发送数据
                val cur_block_len_64b=(cur_block_len_byte>>3)
                dmaWriterIO.in.valid :=  is_queue_has_val && (cur_block_ptr_64b<cur_block_len_64b)
                when(dmaWriterIO.in.fire){
                    // dmaWriterIO.in.bits.data:=data_queue.io.deq.bits
                    cur_block_ptr_64b:=cur_block_ptr_64b+1.U
                    data_queue.io.deq.ready:=true.B
                }

                // 收到回应表示写完了，结束
                when(dmaWriterIO.resp.fire){           
                    v_addr_ptr:=next_page_begin_addr
                    cur_block_ptr_64b:=0.U
                    main_state := pre_process
                }
            }
            
        }

        io.accessArray.valid:=false.B
        val data_arrive=RegNext(io.accessArray.fire)

        // array_ptr_for_enq 用来指示有没有读完所有数据
        // array_ptr_for_accessArray 用来指示读的地址，可能会发生回推的情况
        // array_ptr_for_accessArray:=array_ptr_for_accessArray+1.U 和回退不可能同时发生的
        // 此时必然是accessArray fire的下一个周期
        when(data_arrive)
        {
            // 可能是上一个延迟读写入后没空间了，需要回退array_ptr_for_accessArray
            when(!is_queue_has_space)
            {
                // roll back 上次读的重新读
                array_ptr_for_accessArray:=array_ptr_for_accessArray-1.U
            }
            .otherwise
            {
                data_queue.io.enq.valid:=true.B
                data_queue.io.enq.bits:=io.dataReadFromArray
                array_ptr_for_enq:=array_ptr_for_enq+1.U
            }
        }

        switch(enq_state)
        {
            is(enq_idle)
            {
            }
            is(read_array)
            {
                // (array_ptr_for_accessArray<len_64b)是因为array_ptr_for_accessArray到达时，array_ptr_for_enq不一定到了，会导致多读
                val can_access_array= (array_ptr_for_enq<len_64b)&&is_queue_has_space&&(array_ptr_for_accessArray<len_64b)
                io.accessArray.valid:=can_access_array

                when(io.accessArray.fire)
                {
                    io.accessArray.bits.addr:= baseAddr_Array+array_ptr_for_accessArray
                    io.accessArray.bits.optype:=AccessArrayType.READ
                    array_ptr_for_accessArray:=array_ptr_for_accessArray+1.U
                }

                // End 当且仅单array_ptr_for_enq===len_64b时，才表示所有数据已经入队
                when(array_ptr_for_enq===len_64b)
                {
                    enq_state:=enq_idle
                    array_ptr_for_accessArray:=0.U
                    array_ptr_for_enq:=0.U
                }
            }
        }

    }

}


