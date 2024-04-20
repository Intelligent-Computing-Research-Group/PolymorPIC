package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}


class P2S_L_req(sys_config:Sys_Config) extends Bundle {
    val precision=UInt(3.W)     // 精度
    val next_row_offset_elem=Input(UInt(sys_config._L_row_offset_sigLen.W))
    val base_picAddr_to_store=UInt((sys_config.accessBankFullAddr_sigLen).W) // 6位subarrayID和8位rowID
    val base_dramAddr_to_load=UInt(32.W)
    val _L_block_row=UInt(log2Ceil(sys_config.core_config.max_L_rows_per_block+1).W)
}

class split_data(arrayAddrLen:Int) extends Bundle {
    val split_val=UInt(64.W)
    val arrayAddr=UInt(arrayAddrLen.W)
}

class unsplit_data(dim:Int) extends Bundle {
    val unsplit_split_val=Vec(dim,Vec(8,UInt(8.W)))
}

class P2S_L_ctl(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
{
    val dim=(core_configs.bitlineNums*8/64).toInt  // How many 64b each row
    val max_64b_per_access=(core_configs.bitlineNums*8/64).toInt

    val io = IO(new Bundle {
        // request from rocc
        val p2s_L_req = Flipped(Decoupled(new P2S_L_req(sysCfg)))
        val busy = Output(Bool())

        // request to dma
        val dma_read_req = Decoupled(new DMA_read_req(sysCfg))

        // dma write data to buffer here
        val write_buf_en= Input(Bool())
        val dataFromDMAreader= Input(UInt(64.W))

        // write array
        val accessArray=Decoupled(new ReqPackage(sysCfg))

        // Trace
        val rec_req= if (sysCfg.en_trace) Some(Decoupled(new RecReq(sysCfg))) else None
    })

    // IO deaults
    io.p2s_L_req.ready:=false.B 
    io.dma_read_req.valid:= false.B
    io.dma_read_req.bits.dma_read_type:= DMA_read_type.DMA_P2S_L
    io.dma_read_req.bits.len:= 0.U
    io.dma_read_req.bits.baseAddr_DRAM:= 0.U
    io.dma_read_req.bits.baseAddr_Array:= 0.U
    io.accessArray.valid:=false.B
    io.accessArray.bits.addr:=0.U
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.bits.dataWrittenToBank:=0.U

    // 存放L的一行原数据，不管精度多少，总之够存了
    val regArray = Seq.fill(dim, 8)(Reg(UInt(8.W)))


    val split_data_queue=Module(new Queue(new split_data(sysCfg.accessBankFullAddr_sigLen), 2))
    split_data_queue.io.enq.valid:=false.B
    split_data_queue.io.enq.bits.split_val:=0.U
    split_data_queue.io.enq.bits.arrayAddr:=0.U
    split_data_queue.io.deq.ready:=false.B

    // Register
    val precision=RegInit(0.U(3.W))
    val bits_per_ele=MuxCase(8.U, Seq(
                            (precision===0.U||precision===1.U)->2.U,
                            (precision===2.U||precision===3.U)->4.U,
                            (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->8.U)
                        )
    val base_dram_addr=RegInit(0.U(32.W))
    val base_picAddr=RegInit(0.U((sysCfg.accessBankFullAddr_sigLen).W))
    val pic_write_ptr=RegInit(0.U((sysCfg.accessBankFullAddr_sigLen).W))
    val next_row_offset_elem=RegInit(0.U(sysCfg._L_row_offset_sigLen.W))
    val next_row_offset_dram=(next_row_offset_elem*bits_per_ele)>>log2Ceil(8)
    val bit_ptr=RegInit(0.U(3.W))
    val _L_block_row=RegInit(0.U(log2Ceil(core_configs.max_L_rows_per_block+1).W))
    val _L_block_row_ptr=RegInit(0.U(log2Ceil(core_configs.max_L_rows_per_block+1).W))
    val next_slice_offset_pic=_L_block_row

    val dataIn_reformat_2b=WireInit(VecInit(Seq.fill(32)(0.U(2.W))))
    for(i<- 0 until 32)
    {dataIn_reformat_2b(i):=io.dataFromDMAreader(i*2+1,i*2)}
    val dataIn_reformat_4b=WireInit(VecInit(Seq.fill(16)(0.U(4.W))))
    for(i<- 0 until 16)
    {dataIn_reformat_4b(i):=io.dataFromDMAreader(i*4+3,i*4)}
    val dataIn_reformat_8b=WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
    for(i<- 0 until 8)
    {dataIn_reformat_8b(i):=io.dataFromDMAreader(i*8+7,i*8)}

    // dontTouch(dataIn_reformat_8b)


    val ptr_8_elem = RegInit(0.U(log2Ceil(8+1).W))

    // state
    val p2s_idle :: req_dma :: rev_L_data_one_row :: split_and_enq :: send_trace :: Nil = Enum(5)
    val p2s_state=RegInit(p2s_idle)
    
    val queue_empty  ::write_array :: Nil = Enum(2)
    val deq_state=RegInit(queue_empty)

    io.busy:=  (p2s_state=/=p2s_idle)  || split_data_queue.io.deq.valid 


    val trace_state_some = if (sysCfg.en_trace) Some(RegInit(0.U(io.rec_req.get.bits.rec_state.getWidth.W))) else None
    if(sysCfg.en_trace)
    {
        // Enable tracce config to gen note
        val traceClient_p2sL=new TraceClient(ClientName="P2S_L")
        val io_rec_req = io.rec_req.get
        val trace_state = trace_state_some.get
        io_rec_req.valid:= (p2s_state===send_trace)&(split_data_queue.io.deq.valid===false.B)
        io_rec_req.bits.rec_state:=trace_state
        io_rec_req.bits.picAddr:=base_picAddr
        // Command part is custom
        traceClient_p2sL.addCustomField(field=_L_block_row,
                                     field_name="L_block_row")
        traceClient_p2sL.addCustomField(field=precision,
                                     field_name="precision")
        // 保持与上面一致
        io_rec_req.bits.command:=Cat(precision,_L_block_row)
        // 加到cfg中
        sysCfg.traceCfg.helper.addTraceClient(traceClient_p2sL)

        when(p2s_state===send_trace && io_rec_req.fire)
        {p2s_state:=Mux(trace_state===RecStateType.START_RUN,req_dma,p2s_idle)}
    }
   
    // a dim is 8b
    val elems_per_byte=MuxCase(1.U, Seq(
        (precision===0.U||precision===1.U)->4.U,
        (precision===2.U||precision===3.U)->2.U,
        (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->1.U)
    )
    

    switch(p2s_state)
    {
        is(p2s_idle)
        {
            io.p2s_L_req.ready:=true.B

            when(io.p2s_L_req.fire)
            {
                val req_bits=io.p2s_L_req.bits
                next_row_offset_elem:=req_bits.next_row_offset_elem
                assert((next_row_offset_elem*bits_per_ele&"b111".U)===0.U,"Offset is not times of byte,may need padding.")
                base_dram_addr:=req_bits.base_dramAddr_to_load
                base_picAddr:=req_bits.base_picAddr_to_store
                pic_write_ptr:=req_bits.base_picAddr_to_store
                precision:=req_bits.precision
                _L_block_row:=req_bits._L_block_row
                _L_block_row_ptr:=0.U

                // Triger trace rec !!!!
                if(sysCfg.en_trace)
                {   
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.START_RUN
                    p2s_state:=send_trace  
                }
                else
                {   p2s_state:=req_dma  }
            }

        }
        is(req_dma)       // read a row of block in R_block
        {
            io.dma_read_req.valid:=true.B
            when(io.dma_read_req.fire)
            {
                val cur_64b_len = (bits_per_ele<<log2Ceil(core_configs.bitlineNums))>>6
                assert(cur_64b_len>=1.U,"The row of L should have 64 elem, nomatter what's the precision.")
                io.dma_read_req.bits.len:=cur_64b_len    // 一次只读一行
                io.dma_read_req.bits.baseAddr_DRAM:=base_dram_addr
                base_dram_addr:=base_dram_addr+next_row_offset_dram
                p2s_state:=rev_L_data_one_row
            }
        }
        is(rev_L_data_one_row)
        {
            // 每次立即将收到的8个byte写入unsplit的buf，一个ptr等于8个小reg
            // 1个dim就是一个64b
            val ptr_8_elem_inc=MuxCase(1.U, Seq(
                (precision===0.U||precision===1.U)->4.U,
                (precision===2.U||precision===3.U)->2.U,
                (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->1.U)
            )
            val valid_dim_id_mask=MuxCase(1.U, Seq(
                (precision===0.U||precision===1.U)->"b100".U,
                (precision===2.U||precision===3.U)->"b110".U,
                (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->"b111".U)
            )
            when(io.write_buf_en)
            {
                // 存入buf，ptr加1
                for(dim_ptr <- 0 until dim)
                {
                    // 每次来8个bytes
                    when((dim_ptr.U & valid_dim_id_mask)===ptr_8_elem)
                    {
                        for(i <- 0 until 8)
                        {
                            regArray(dim_ptr)(i):=MuxCase(1.U, Seq(
                                (precision===0.U||precision===1.U)->dataIn_reformat_2b(dim_ptr%4*8+i),
                                (precision===2.U||precision===3.U)->dataIn_reformat_4b(dim_ptr%2*8+i),
                                (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->dataIn_reformat_8b(i))
                            )
                        }
                    }
                }
                
                ptr_8_elem:=ptr_8_elem+ptr_8_elem_inc
            }

            when(ptr_8_elem>(dim-1).U)
            {
                ptr_8_elem:=0.U
                bit_ptr:=0.U
                pic_write_ptr:=base_picAddr+_L_block_row_ptr
                p2s_state:=split_and_enq
            }
        }
        is(split_and_enq)  // 切分并且入队
        {
            split_data_queue.io.enq.valid:=true.B
            when(split_data_queue.io.enq.fire)
            {

                split_data_queue.io.enq.bits.arrayAddr:=pic_write_ptr
                split_data_queue.io.enq.bits.split_val:=extractBits(regArray,bit_ptr,dim)

                // jump to next slice
                pic_write_ptr:=pic_write_ptr+next_slice_offset_pic
                bit_ptr:=bit_ptr+1.U


                when(bit_ptr===precision)
                {
                    _L_block_row_ptr:=_L_block_row_ptr+1.U
                    if(sysCfg.en_trace)
                    {
                        val trace_state = trace_state_some.get
                        trace_state:=RecStateType.END_RUN
                        p2s_state:=Mux(_L_block_row_ptr===_L_block_row-1.U,send_trace,req_dma)  
                    }
                    else
                    {p2s_state:=Mux(_L_block_row_ptr===_L_block_row-1.U,p2s_idle,req_dma)}
                    
                }
            }
        }
    }



    val is_queue_empty = !split_data_queue.io.deq.valid
    val is_queue_has_val = split_data_queue.io.deq.valid
    switch(deq_state)
    {
        is(queue_empty)
        {
            // queue has data
            when(split_data_queue.io.deq.valid)
            {
                deq_state:=write_array
            }
        }
        is(write_array)
        {
            io.accessArray.valid:=is_queue_has_val
            when(io.accessArray.fire)
            {
                // deq
                split_data_queue.io.deq.ready:=true.B
                io.accessArray.bits.dataWrittenToBank:=split_data_queue.io.deq.bits.split_val
                io.accessArray.bits.optype:=AccessArrayType.WRITE
                io.accessArray.bits.addr:=split_data_queue.io.deq.bits.arrayAddr
            }

            when(is_queue_empty)
            {
                deq_state:=queue_empty
            }

        }
    }


    def extractBits(array: Seq[Seq[UInt]], bit: UInt, dim:Int): UInt = {
        val res=WireInit(VecInit(Seq.fill(64)(0.U(8.W))))
        for(i<-0 until 64)
        {
            res(i):=array((i/8).toInt)((i%8).toInt)
        }

        Cat(res.map(_(bit)).reverse)
    }
        

}