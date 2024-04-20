package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}

class P2S_R_req(sys_config:Sys_Config) extends Bundle {
    val num_column=UInt(log2Ceil(sys_config.core_config.wordlineNums+1).W)    // 要读取几列，最大1024
    val precision=UInt(3.W)     // 每个数的精度是多少
    val next_row_offset_elem=Input(UInt(sys_config._R_row_offset_sigLen.W))    // 1byte
    val base_arrayID_to_store=UInt(log2Ceil(sys_config.total_array_nums).W) // Which subarray to put the first selected bit map
    val bufNum=UInt(2.W)
    val dramAddr=UInt(32.W)
    // val transpose=Bool()
}

object MemState
{
    val EMPTY=0.U
    val WRITING=1.U
    val CAN_READ=2.U
}


class P2S_R_ctl(sysCfg:Sys_Config,coreCfg:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
{
    val io = IO(new Bundle {
        // request from rocc
        val p2s_R_req = Flipped(Decoupled(new P2S_R_req(sysCfg)))
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

    // buffer array 固定为64行 8列 ，每个元素是8b
    val bufRow=64
    val bufCol=8
    val regArray = Seq.fill(bufRow, bufCol)(Reg(UInt(8.W)))
    val regArrayT = regArray.transpose

    // Some registers
    // Static
    val bits_per_elem=8
    val max_supported_ram_block_col_64b= (sysCfg.p2sR_mem_depth/64).toInt       // 最大支持的每行长度
    val max_supported_ram_block_col_elem= (sysCfg.p2sR_mem_depth/64*64/8).toInt
    val total_num_column_of_R=RegInit(0.U(log2Ceil(coreCfg.wordlineNums+1).W))       // 整个R有几列，单位是byte
    val next_row_offset_elem=RegInit(0.U(sysCfg._R_row_offset_sigLen.W))    // 两行之间相差几个元素
    val next_row_offset_dram=(next_row_offset_elem*bits_per_elem.U)>>3        // 两行之间相差的地址
    val precision=RegInit(0.U(3.W))                                         // 元素精度，是-1的
    val base_arrayID=RegInit(0.U(log2Ceil(sysCfg.total_array_nums).W))      // 第一个array的地址，不涉及哪一行
    // Dynamic
    val cur_block_col_num_ele=RegInit(0.U(log2Ceil(coreCfg.wordlineNums+1).W))         // 当前在处理的这个R的block的每行的元素个数
    val cur_block_col_num_64b=(cur_block_col_num_ele*bits_per_elem.U)>>log2Ceil(64)               // 当前在处理的这个R的block的每行的64b数
    val cur_block_col_elem_ptr=RegInit(0.U(log2Ceil(coreCfg.wordlineNums).W))         // 当前在处理的这个block的第一列是整个矩阵的第几列
    val cur_block_row_ptr=RegInit(0.U(log2Ceil(64+1).W))                          // 当前在处理的这个block的这一行是哪一行
    val cur_block_baseDramAddr=RegInit(0.U(32.W))                           // 当前在处理的这个block的第一个元素（左上角）的ram地址
    val cur_row_dramAddr_ptr=RegInit(0.U(32.W))                            // 当前在处理的这一行的首地址
    val buf_64b_count_write_mem=RegInit(0.U(log2Ceil(max_supported_ram_block_col_64b).W))  // 当前从dma接收到的64b是本行的第几个

    // memory 共两个交替使用
    // mem有状态 EMPTY WRITING CAN_READ
    val mem0 =  Module(new SRAM8(word_num=sysCfg.p2sR_mem_depth,io_width=64))
    val mem0_state = RegInit(0.U(2.W))
    val enWire_0 = WireInit(false.B)
    val writeEnWire_0=WireInit(false.B)
    val addrWire_0=WireInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))
    
    val mem1 =  Module(new SRAM8(word_num=sysCfg.p2sR_mem_depth,io_width=64))
    val mem1_state = RegInit(0.U(2.W))
    val enWire_1=WireInit(false.B)
    val writeEnWire_1=WireInit(false.B)
    val addrWire_1=WireInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))

    val write_mem_addr=RegInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))
    val read_mem_addr=RegInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))

    mem0.io.en:=enWire_0
    mem0.io.writeEn:=writeEnWire_0
    mem0.io.addr:=Mux(mem0_state===MemState.WRITING,write_mem_addr,read_mem_addr)
    mem0.io.dataIn:=io.dataFromDMAreader

    mem1.io.en:=enWire_1
    mem1.io.writeEn:=writeEnWire_1
    mem1.io.addr:=Mux(mem1_state===MemState.WRITING,write_mem_addr,read_mem_addr)
    mem1.io.dataIn:=io.dataFromDMAreader

    // 选择的是哪个memory
    val dma_write_mem_chose = RegInit(0.U(1.W))
    val p2s_read_memory_chose = RegInit(0.U(1.W))

    // R的两行在mem中相间隔的行数(64b)，即R矩阵的每个block每行的长度
    val mem0_block_interval_64b=RegInit(0.U(log2Ceil(max_supported_ram_block_col_64b+1).W))
    val mem1_block_interval_64b=RegInit(0.U(log2Ceil(max_supported_ram_block_col_64b+1).W))
    val choosed_mem_block_interval=Mux(p2s_read_memory_chose===0.U,mem0_block_interval_64b,mem1_block_interval_64b)

    // Array offset
    val arrayID_offset=RegInit(VecInit(Seq.fill(8)(0.U(5.W))))      // 相对于第0bit的offset
    val relative_offset_buf=WireInit(VecInit(Seq.fill(7)(0.U(4.W))))
    val req_buf_num=io.p2s_R_req.bits.bufNum
    relative_offset_buf(0):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))
    relative_offset_buf(1):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->1.U))
    relative_offset_buf(2):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->2.U))
    relative_offset_buf(3):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->1.U))
    relative_offset_buf(4):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))
    relative_offset_buf(5):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->2.U))
    relative_offset_buf(6):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))

    val rev_idle :: pre_process_cur_block :: wait_mem_free :: req_dma :: write_mem_one_block_row :: update_cursor :: send_trace :: Nil = Enum(7)
    val REV_STATE=RegInit(rev_idle)

    val write_pic_idle :: check_readable_memory :: read_mem_and_write_buf :: split_data_enq :: update_write_pic_cursor :: Nil = Enum(5)
    val BUF_OP_STATE=RegInit(write_pic_idle)

    val queue_empty  :: write_array :: Nil = Enum(2)
    val deq_state=RegInit(queue_empty)

    val split_data_queue=Module(new Queue(new split_data(sysCfg.accessBankFullAddr_sigLen), 8))
    split_data_queue.io.enq.valid:=(BUF_OP_STATE===split_data_enq&RegNext(BUF_OP_STATE)=/=read_mem_and_write_buf)
    split_data_queue.io.enq.bits.split_val:=0.U
    split_data_queue.io.enq.bits.arrayAddr:=0.U
    split_data_queue.io.deq.ready:=false.B

    io.busy:= (REV_STATE=/=rev_idle) || split_data_queue.io.deq.valid || (BUF_OP_STATE=/=write_pic_idle)


    io.dma_read_req.valid:=(REV_STATE===req_dma)
    io.p2s_R_req.ready:=(REV_STATE===rev_idle)


    io.dma_read_req.bits.baseAddr_Array:= DontCare
    io.dma_read_req.bits.len:=cur_block_col_num_64b    // 至少也要读一个64b
    io.dma_read_req.bits.baseAddr_DRAM:=cur_row_dramAddr_ptr
    io.dma_read_req.bits.dma_read_type:= DMA_read_type.DMA_P2S_R

    // Trace 
    val trace_state_some = if (sysCfg.en_trace) Some(RegInit(0.U(io.rec_req.get.bits.rec_state.getWidth.W))) else None
    val buf_num_reg_some = if (sysCfg.en_trace) Some(RegInit(0.U(2.W))) else None
    if(sysCfg.en_trace)
    {
        // Enable tracce config to gen note
        val traceClient_p2sR=new TraceClient(ClientName="P2S_R")
        val io_rec_req = io.rec_req.get
        val trace_state = trace_state_some.get
        io_rec_req.valid:= (REV_STATE===send_trace)&&(split_data_queue.io.deq.valid===false.B)&&
                            ((trace_state===RecStateType.END_RUN&BUF_OP_STATE===write_pic_idle)||(trace_state===RecStateType.START_RUN))
        io_rec_req.bits.rec_state:=trace_state
        io_rec_req.bits.picAddr:=(base_arrayID<<sysCfg.core_config.array_addrWidth)

        // Command part is custom
        val req_data=io.p2s_R_req.bits
        traceClient_p2sR.addCustomField(field=buf_num_reg_some.get,
                                     field_name="bufNum")
        traceClient_p2sR.addCustomField(field=precision,
                                     field_name="precision")
        // 保持与上面一致
        io_rec_req.bits.command:=Cat(precision,buf_num_reg_some.get)
        // 加到cfg中
        sysCfg.traceCfg.helper.addTraceClient(traceClient_p2sR)

        when(REV_STATE===send_trace && io_rec_req.fire)
        {REV_STATE:=Mux(trace_state===RecStateType.START_RUN,pre_process_cur_block,rev_idle)}
    }

    switch(REV_STATE)
    {
        is(rev_idle)
        {
            when(io.p2s_R_req.fire)
            {
                val req_bits=io.p2s_R_req.bits
                total_num_column_of_R:=req_bits.num_column
                assert(((req_bits.next_row_offset_elem*bits_per_elem.U) & "b111".U) === 0.U,
                            "The length of each row must be an integer multiple of 8b in size in DRAM!!!")
                next_row_offset_elem:=req_bits.next_row_offset_elem
                base_arrayID:=req_bits.base_arrayID_to_store
                precision:=req_bits.precision
                cur_block_baseDramAddr:=req_bits.dramAddr
                cur_block_col_elem_ptr:=0.U
                write_mem_addr:=0.U

                // 计算array间偏移
                val tempResults = Wire(Vec(8, UInt(5.W)))
                tempResults(0) := 0.U
                for(i <- 1 until 8)
                {tempResults(i):=relative_offset_buf((i-1))+tempResults(i-1)}
                arrayID_offset:=tempResults

                // write which memory 初始化从第0个开始
                dma_write_mem_chose:=0.U
                mem0_state:= MemState.EMPTY
                mem1_state:= MemState.EMPTY

                REV_STATE:=pre_process_cur_block

                // Triger trace rec !!!! Start
                if(sysCfg.en_trace)
                {   
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.START_RUN
                    REV_STATE:=send_trace  
                    buf_num_reg_some.get:=io.p2s_R_req.bits.bufNum
                }
                else
                {   REV_STATE:=pre_process_cur_block  }

            }
        }
        is(pre_process_cur_block)
        {
            // 计算当前处理的这块block每行的元素个数
            cur_block_col_num_ele:=Mux((cur_block_col_elem_ptr+max_supported_ram_block_col_elem.U)<=total_num_column_of_R,
                                        max_supported_ram_block_col_elem.U,
                                        total_num_column_of_R-cur_block_col_elem_ptr)
            cur_row_dramAddr_ptr:=cur_block_baseDramAddr
            cur_block_row_ptr:=0.U
            REV_STATE:=wait_mem_free
        }
        // 等待空余的memory
        is(wait_mem_free)
        {
            val mem0_can_write= (mem0_state===MemState.EMPTY)
            val mem1_can_write= (mem1_state===MemState.EMPTY)
            val mem_can_write_this_turn_to_check=Mux(dma_write_mem_chose===0.U,mem0_can_write,mem1_can_write)
            mem0_state:=Mux(mem_can_write_this_turn_to_check&&dma_write_mem_chose===0.U,MemState.WRITING,mem0_state)        // 通知buf可以read
            mem1_state:=Mux(mem_can_write_this_turn_to_check&&dma_write_mem_chose===1.U,MemState.WRITING,mem1_state)        
            REV_STATE:=Mux(mem_can_write_this_turn_to_check,req_dma,REV_STATE)
        }
        is(req_dma)
        {
            when(io.dma_read_req.fire)
            {
                assert(cur_block_col_num_64b>=1.U,"Current doesn't support such R's column num.")
                buf_64b_count_write_mem:=0.U
                REV_STATE:=write_mem_one_block_row
            }
        }
        is(write_mem_one_block_row)
        {
            when(io.write_buf_en)
            {
                when(dma_write_mem_chose===0.U)
                {
                    enWire_0:=true.B
                    writeEnWire_0:=true.B
                }
                when(dma_write_mem_chose===1.U)
                {
                    enWire_1:=true.B
                    writeEnWire_1:=true.B
                }
                // check
                val this_row_end = (buf_64b_count_write_mem===(cur_block_col_num_64b-1.U))  // 本行全部加载完了
                buf_64b_count_write_mem:=buf_64b_count_write_mem+1.U
                cur_block_row_ptr:=Mux(this_row_end,cur_block_row_ptr+1.U,cur_block_row_ptr)
                write_mem_addr:=write_mem_addr+1.U
                REV_STATE:=Mux(this_row_end,update_cursor,REV_STATE)
            }
        }
        is(update_cursor)
        {
            // 次block是否加载完了
            val has_next_row = !(cur_block_row_ptr===64.U)
            // 整个R是不是加载完了
            val all_finish = (cur_block_col_elem_ptr+cur_block_col_num_ele===(total_num_column_of_R))
            
            // 计算下一行的dram地址，是当前的再加上每行长度
            cur_row_dramAddr_ptr:=cur_row_dramAddr_ptr+next_row_offset_elem
            // 如果当前block结束了
            // 1.那么就要更新block的基地址到下一个block
            // 2.要写的memID更新
            // 3.更新当前mem状态，表示可以read了
            // 4.更新当前的cur_block_col_elem_ptr
            cur_block_baseDramAddr:=Mux(has_next_row,cur_block_baseDramAddr,cur_block_baseDramAddr+cur_block_col_num_ele)   // 没有下一行了，表示当前block结束
            dma_write_mem_chose:=Mux(has_next_row===false.B,dma_write_mem_chose+1.U,dma_write_mem_chose)    // 当前block结束 下次选择另一个mem
            mem0_state:=Mux(has_next_row===false.B&&dma_write_mem_chose===0.U,MemState.CAN_READ,mem0_state)        // 通知buf可以read
            mem1_state:=Mux(has_next_row===false.B&&dma_write_mem_chose===1.U,MemState.CAN_READ,mem1_state)        
            mem0_block_interval_64b:=Mux((has_next_row===false.B)&(dma_write_mem_chose===0.U),
                                            cur_block_col_num_64b,mem0_block_interval_64b) // 填入偏移
            mem1_block_interval_64b:=Mux((has_next_row===false.B)&(dma_write_mem_chose===1.U),
                                            cur_block_col_num_64b,mem1_block_interval_64b)
            cur_block_col_elem_ptr:=Mux(has_next_row,cur_block_col_elem_ptr,cur_block_col_elem_ptr+cur_block_col_num_ele)
            
            // Triger trace rec !!!! End
            if(sysCfg.en_trace)
            {
                val trace_state = trace_state_some.get
                trace_state:=RecStateType.END_RUN
                REV_STATE:=Mux(all_finish&(has_next_row===false.B),send_trace,
                                    Mux(has_next_row,req_dma,pre_process_cur_block))
            }
            else
            {
                REV_STATE:=Mux(all_finish&(has_next_row===false.B),rev_idle,
                                    Mux(has_next_row,req_dma,pre_process_cur_block))
            }
            
        }
    }


    val write_buf_row_ptr=RegInit(0.U(log2Ceil(64).W))              // 往buf中的哪一行写数据
    val block_R_col_ptr_64b=RegInit(0.U(log2Ceil(max_supported_ram_block_col_64b).W))   // 当前buf中的数据是R分块中的哪一列

    val bit_ptr=RegInit(0.U(3.W))           // 写那个精度的分片
    val split_bufT_row_ptr=RegInit(0.U(log2Ceil(bufCol).W))        // 当前操作元素是转置后的buffer的第几行
    val cur_write_array_base=RegInit(0.U(log2Ceil(coreCfg.wordlineNums+1).W))


    // Write buf logic
    val mem0_out_reformat=WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
    val mem1_out_reformat=WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
    for(i<-0 until 8)
    {
        mem0_out_reformat(i):=mem0.io.dataOut(i*8+7,i*8)
        mem1_out_reformat(i):=mem1.io.dataOut(i*8+7,i*8)
    }

    val choosed_mem_dataOut=Mux(p2s_read_memory_chose===0.U,mem0_out_reformat,
                                                            mem1_out_reformat)
    for(buf_row<- 0 until 64)
    {
        val choosed_mem_read_signal= Mux(p2s_read_memory_chose===0.U, 
                                        enWire_0&(!writeEnWire_0),enWire_1&(!writeEnWire_1))
        val can_save = RegNext(choosed_mem_read_signal)&RegNext(write_buf_row_ptr===buf_row.U)
        for(buf_col<-0 until 8)
        {
            regArray(buf_row)(buf_col):=Mux(can_save,choosed_mem_dataOut(buf_col),regArray(buf_row)(buf_col))
        }
    }
        
    switch(BUF_OP_STATE)
    {
        is(write_pic_idle)
        {
            // free until 有请求
            when(io.p2s_R_req.fire)
            {
                // 初始必须从0号开始
                p2s_read_memory_chose:=0.U
                cur_write_array_base:=0.U
                BUF_OP_STATE:=check_readable_memory
            }
        }
        is(check_readable_memory)
        {
            val mem0_can_read= (mem0_state===MemState.CAN_READ)
            val mem1_can_read= (mem1_state===MemState.CAN_READ)
            val mem_can_read_this_turn_to_check=Mux(p2s_read_memory_chose===0.U,mem0_can_read,mem1_can_read)
            read_mem_addr:=0.U
            bit_ptr:=0.U
            BUF_OP_STATE:=Mux(mem_can_read_this_turn_to_check,read_mem_and_write_buf,BUF_OP_STATE)
        }
        is(read_mem_and_write_buf)
        {
            // which mem
            when(p2s_read_memory_chose===0.U)
            {
                enWire_0:=true.B
                writeEnWire_0:=false.B
            }
            when(p2s_read_memory_chose===1.U)
            {
                enWire_1:=true.B
                writeEnWire_1:=false.B
            }
            // addr update
            val should_finish=(write_buf_row_ptr===(64-1).U)
            write_buf_row_ptr:=Mux(should_finish,0.U,write_buf_row_ptr+1.U)
            read_mem_addr:=Mux(should_finish,block_R_col_ptr_64b+1.U,read_mem_addr+choosed_mem_block_interval)
            BUF_OP_STATE:=Mux(should_finish,split_data_enq,BUF_OP_STATE)
        }
        is(split_data_enq)
        {
            when(split_data_queue.io.enq.fire)
            {
                val cur_arrayID=MuxCase(0.U,(0 until 8).map { bit=>
                    (bit.U===bit_ptr) -> (base_arrayID+arrayID_offset(bit))
                })
                val arrayAddr_enq=(cur_arrayID<<log2Ceil(coreCfg.wordlineNums))+
                                    cur_write_array_base+split_bufT_row_ptr

                split_data_queue.io.enq.bits.split_val:=extractBits(
                                                        regArrayT,split_bufT_row_ptr,bit_ptr,dim=8)
                split_data_queue.io.enq.bits.arrayAddr:=arrayAddr_enq
            
                bit_ptr:=bit_ptr+1.U

                // 当前bufT行的元素的所有精度都写回了
                when(bit_ptr===precision)
                {
                    bit_ptr:=0.U
                    split_bufT_row_ptr:=split_bufT_row_ptr+1.U
                    // 当前bufT中所有行都写好了
                    when(split_bufT_row_ptr===(bufCol-1).U)
                    {
                        split_bufT_row_ptr:=0.U
                        cur_write_array_base:=cur_write_array_base+8.U
                        // 查看是memory中的最后一列吗
                        val last_64b_in_mem=(block_R_col_ptr_64b===(choosed_mem_block_interval-1.U))
                        block_R_col_ptr_64b:=Mux(last_64b_in_mem,0.U,block_R_col_ptr_64b+1.U)
                        BUF_OP_STATE:=Mux(last_64b_in_mem,update_write_pic_cursor,read_mem_and_write_buf)
                    }
                }
            }
        }
        is(update_write_pic_cursor)
        {
            mem0_state:=Mux(p2s_read_memory_chose===0.U,MemState.EMPTY,mem0_state)
            mem1_state:=Mux(p2s_read_memory_chose===1.U,MemState.EMPTY,mem1_state)
            p2s_read_memory_chose:=p2s_read_memory_chose+1.U

            val no_data=(cur_write_array_base===total_num_column_of_R)
            BUF_OP_STATE:=Mux(no_data,write_pic_idle,check_readable_memory)
        }
    }

    val is_queue_empty = !split_data_queue.io.deq.valid
    val is_queue_has_val = split_data_queue.io.deq.valid
    io.accessArray.valid := is_queue_has_val&(deq_state===write_array)
    io.accessArray.bits.dataWrittenToBank:=split_data_queue.io.deq.bits.split_val
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.bits.addr:=split_data_queue.io.deq.bits.arrayAddr
    switch(deq_state)
    {
        is(queue_empty)
        {
            // queue has data
            when(split_data_queue.io.deq.valid)
            { deq_state:=write_array }
        }
        is(write_array)
        {
            when(io.accessArray.fire)
            { split_data_queue.io.deq.ready:=true.B}

            when(is_queue_empty)
            { deq_state:=queue_empty}
        }
    }

    def extractBits(arrayT: Seq[Seq[UInt]], row: UInt, bit: UInt, dim:Int): UInt = {
        val res=WireInit(VecInit(Seq.fill(64)(0.U(8.W))))
        for(row_ptr<-0 until dim)
        {
            when(row_ptr.U===row)
            {
                for(col_ptr <- 0 until 64)
                {
                    res(col_ptr):=arrayT(row_ptr)(col_ptr)
                }
            }
            
        }
        Cat(res.map(_(bit)).reverse)
    }
}