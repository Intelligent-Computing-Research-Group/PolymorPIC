// package PIC

// import chisel3._
// import chisel3.util._
// import freechips.rocketchip.tile._
// import org.chipsalliance.cde.config._
// import freechips.rocketchip.util.{DontTouch}

// class P2S_R_req(sys_config:Sys_Config) extends Bundle {
//     val num_column=UInt(log2Ceil(sys_config.core_config.wordlineNums+1).W)    // 要读取几列，最大1024
//     val precision=UInt(3.W)     // 每个数的精度是多少
//     val next_row_offset_elem=Input(UInt(sys_config._R_row_offset_sigLen.W))    // 1byte
//     val base_arrayID_to_store=UInt(log2Ceil(sys_config.total_array_nums).W) // Which subarray to put the first selected bit map
//     val bufNum=UInt(2.W)
//     val dramAddr=UInt(32.W)
// }


// class P2S_R_ctl(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
// {
//     val dim=sysCfg.transposeBufCol
//     val max_64b_per_access=(dim/8).toInt
//     val arrayAddrLen=log2Ceil(sysCfg.bank_num)+log2Ceil(sysCfg.mat_per_bank)+log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
//     val io = IO(new Bundle {
//         // request from rocc
//         val p2s_R_req = Flipped(Decoupled(new P2S_R_req(sysCfg)))
//         val busy = Output(Bool())

//         // request to dma
//         val dma_read_req = Decoupled(new DMA_read_req(sysCfg))

//         // dma write data to buffer here
//         val write_buf_en= Input(Bool())
//         val dataFromDMAreader= Input(UInt(64.W))

//         // write array
//         val accessArray=Decoupled(new ReqPackage(sysCfg))

//         // Trace
//         val rec_req= if (sysCfg.en_trace) Some(Decoupled(new RecReq(sysCfg))) else None
//     })

//     // IO deaults
//     io.p2s_R_req.ready:=false.B 
//     io.dma_read_req.valid:= false.B
//     io.dma_read_req.bits.dma_read_type:= DMA_read_type.DMA_P2S_R
//     io.dma_read_req.bits.len:= 0.U
//     io.dma_read_req.bits.baseAddr_DRAM:= 0.U
//     io.dma_read_req.bits.baseAddr_Array:= 0.U
//     io.accessArray.valid:=false.B
//     io.accessArray.bits.addr:=0.U
//     io.accessArray.bits.optype:=AccessArrayType.WRITE
//     io.accessArray.bits.dataWrittenToBank:=0.U

//     val regArray = Seq.fill(64, dim)(Reg(UInt(8.W)))
//     val regArrayT = regArray.transpose
    
//     val split_data_queue=Module(new Queue(new split_data(arrayAddrLen), 8))
//     split_data_queue.io.enq.valid:=false.B
//     split_data_queue.io.enq.bits.split_val:=0.U
//     split_data_queue.io.enq.bits.arrayAddr:=0.U
//     split_data_queue.io.deq.ready:=false.B


//     val p2s_idle :: pre_process_cur_block :: pre_process_cur_block_row :: req_dma :: rev_R_data_one_row ::post_check::write_array_queue::send_trace::Nil = Enum(8)
//     val p2s_state=RegInit(p2s_idle)
//     val p2s_last_state=RegInit(p2s_idle)

//     val queue_empty  ::write_array :: Nil = Enum(2)
//     val deq_state=RegInit(queue_empty)

//     val cur_len_64b=RegInit(0.U(log2Ceil(max_64b_per_access).W))
//     val precision=RegInit(0.U(3.W))

//     val bits_per_ele=MuxCase(8.U, Seq(
//                             (precision===0.U||precision===1.U)->2.U,
//                             (precision===2.U||precision===3.U)->4.U,
//                             (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->8.U)
//                         )
//     val num_column_of_R=RegInit(0.U(11.W))
//     val num_row_of_L=RegInit(0.U(11.W))
//     val next_row_offset_elem=RegInit(0.U(sysCfg._R_row_offset_sigLen.W))
//     val next_row_offset_dram=(next_row_offset_elem*bits_per_ele)>>3
//     val base_arrayID=RegInit(0.U(log2Ceil(sysCfg.total_array_nums).W))  // R
//     val which_array_ptr=RegInit(0.U(log2Ceil(sysCfg.total_array_nums).W))  // R
//     val arrayID_offset=RegInit(VecInit(Seq.fill(8)(0.U(5.W))))      // 相对于第0bit的offset
//     val relative_offset_buf=WireInit(VecInit(Seq.fill(7)(0.U(4.W))))
//     val req_buf_num=io.p2s_R_req.bits.bufNum
//     relative_offset_buf(0):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))
//     relative_offset_buf(1):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->1.U))
//     relative_offset_buf(2):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->2.U))
//     relative_offset_buf(3):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->1.U))
//     relative_offset_buf(4):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))
//     relative_offset_buf(5):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->3.U,(req_buf_num===1.U)->2.U))
//     relative_offset_buf(6):=MuxCase(1.U, Seq((req_buf_num===3.U)->4.U,(req_buf_num===2.U)->1.U,(req_buf_num===1.U)->1.U))
    
//     val elems_per_dim=MuxCase(1.U, Seq(
//         (precision===0.U||precision===1.U)->4.U,
//         (precision===2.U||precision===3.U)->2.U,
//         (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->1.U)
//     )
//     val base_array_row_ID=RegInit(0.U((sysCfg.accessBankFullAddr_sigLen).W)) // L
//     val col_num_per_block_sigLen=log2Ceil(dim*4)+1
//     val cur_block_col_ptr=RegInit(0.U(col_num_per_block_sigLen.W))
//     val cur_block_col_num_ele=RegInit(0.U(col_num_per_block_sigLen.W))
//     val cur_block_col_elem_ptr_base=RegInit(0.U(log2Ceil(core_configs.wordlineNums+1).W))
//     val dma_block_base_addr=RegInit(0.U(32.W))  // 每个block左上角dram地址
//     val dma_addr_ptr=RegInit(0.U(32.W))
//     val dram_row_ptr=RegInit(0.U(6.W))
//     val buf_row_ptr=RegInit(0.U(log2Ceil(64).W))
//     val buf_block_base_col=RegInit(0.U(log2Ceil(sysCfg.transposeBufCol).W))
//     val buf_64b_count_per_row=RegInit(0.U(6.W)) // 长度128*8/64=16 TODO 参数化
//     val dataIn_reformat=WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
//     for(i<-0 until 8)
//     {dataIn_reformat(i):=io.dataFromDMAreader(i*8+7,i*8)}
//     // buf splt ptr
//     // The 
//     val bit_ptr=RegInit(0.U(3.W))   // which bit
//     val buf_cell_inner_elem_ptr=RegInit(0.U(log2Ceil(8).W))   // which elem in the uint8
//     val buf_elem_ptr=RegInit(0.U(log2Ceil(sysCfg.transposeBuf_max_elem_col).W))

//     val busy = (p2s_state=/=p2s_idle) || split_data_queue.io.deq.valid
//     io.busy:= busy

//     // Trace 
//     val trace_state_some = if (sysCfg.en_trace) Some(RegInit(0.U(io.rec_req.get.bits.rec_state.getWidth.W))) else None
//     val buf_num_reg_some = if (sysCfg.en_trace) Some(RegInit(0.U(2.W))) else None
//     if(sysCfg.en_trace)
//     {
//         // Enable tracce config to gen note
//         val traceClient_p2sR=new TraceClient(ClientName="P2S_R")
//         val io_rec_req = io.rec_req.get
//         val trace_state = trace_state_some.get
//         io_rec_req.valid:= (p2s_state===send_trace)&&(split_data_queue.io.deq.valid===false.B)
//         io_rec_req.bits.rec_state:=trace_state
//         io_rec_req.bits.picAddr:=(base_arrayID<<sysCfg.core_config.array_addrWidth)

//         // Command part is custom
//         val req_data=io.p2s_R_req.bits
//         traceClient_p2sR.addCustomField(field=buf_num_reg_some.get,
//                                      field_name="bufNum")
//         traceClient_p2sR.addCustomField(field=precision,
//                                      field_name="precision")
//         // 保持与上面一致
//         io_rec_req.bits.command:=Cat(precision,buf_num_reg_some.get)
//         // 加到cfg中
//         sysCfg.traceCfg.helper.addTraceClient(traceClient_p2sR)

//         when(p2s_state===send_trace && io_rec_req.fire)
//         {p2s_state:=Mux(trace_state===RecStateType.START_RUN,pre_process_cur_block,p2s_idle)}
//     }

//     switch(p2s_state)
//     {
//         is(p2s_idle)
//         {
//             io.p2s_R_req.ready:=true.B
//             buf_block_base_col:=0.U
//             cur_block_col_num_ele:=0.U
//             cur_block_col_elem_ptr_base:=0.U

//             when(io.p2s_R_req.fire)
//             {
//                 val req_bits=io.p2s_R_req.bits
//                 num_column_of_R:=req_bits.num_column
//                 assert(((req_bits.next_row_offset_elem*bits_per_ele) & "b111".U) === 0.U,
//                             "The length of each row must be an integer multiple of 8b in size in DRAM!!!")
//                 next_row_offset_elem:=req_bits.next_row_offset_elem
//                 base_arrayID:=req_bits.base_arrayID_to_store
//                 which_array_ptr:=req_bits.base_arrayID_to_store
//                 precision:=req_bits.precision
//                 dma_block_base_addr:=req_bits.dramAddr
//                 dma_addr_ptr:=req_bits.dramAddr
//                 p2s_last_state:=p2s_idle

//                 // 计算array间偏移
//                 val tempResults = Wire(Vec(8, UInt(5.W)))
//                 tempResults(0) := 0.U
//                 for(i <- 1 until 8)
//                 {tempResults(i):=relative_offset_buf((i-1))+tempResults(i-1)}
//                 arrayID_offset:=tempResults

//                 // Triger trace rec !!!! Start
//                 if(sysCfg.en_trace)
//                 {   
//                     val trace_state = trace_state_some.get
//                     trace_state:=RecStateType.START_RUN
//                     p2s_state:=send_trace  
//                     buf_num_reg_some.get:=io.p2s_R_req.bits.bufNum
//                 }
//                 else
//                 {   p2s_state:=pre_process_cur_block  }
//             }

//         }
//         is(pre_process_cur_block)   // 对于每个block,计算当前分块长度
//         {
//             cur_block_col_num_ele:=Mux((cur_block_col_elem_ptr_base+(dim.U)*elems_per_dim)<=num_column_of_R,
//                                         (dim.U)*elems_per_dim,
//                                         num_column_of_R-cur_block_col_elem_ptr_base)
//             dma_block_base_addr:=dma_block_base_addr+((cur_block_col_num_ele*bits_per_ele)>>3)  // 更新dram base指向下一个block开头，每次加的cur_block_col_num_ele是上一次的长度，初始为0
//             // buf的行指针，指明当前操作的是64行中的哪行
//             buf_row_ptr:=0.U
//             p2s_last_state:=pre_process_cur_block
//             p2s_state:=pre_process_cur_block_row
//         }
//         is(pre_process_cur_block_row)    // 对于block的每行
//         {
//             // 更新dma_addr_ptr指针,如果是第一行，那么初始化为block的base
//             dma_addr_ptr:=Mux(p2s_last_state===pre_process_cur_block,
//                                 dma_block_base_addr,
//                                 dma_addr_ptr+next_row_offset_dram)
//             // 恢复col指针 ,该指针用于指明操作的是改行的哪个buf cell，它每次递增8，因为一次写入64b=8个buf单元
//             cur_block_col_ptr:=0.U
//             p2s_state:=req_dma
//             p2s_last_state:=pre_process_cur_block_row
//         }
//         is(req_dma)       // read a row of block in R_block
//         {
//             io.dma_read_req.valid:=true.B
//             when(io.dma_read_req.fire)
//             {
//                 val cur_64b_len = ((cur_block_col_num_ele*bits_per_ele)>>6) // 6 means 64b
//                 assert(cur_64b_len>=1.U,"Current doesn't support such R's column num.")
//                 io.dma_read_req.bits.len:=cur_64b_len    // 至少也要读一个64b
//                 io.dma_read_req.bits.baseAddr_DRAM:=dma_addr_ptr
//                 buf_64b_count_per_row:=0.U
//                 p2s_state:=rev_R_data_one_row
//                 p2s_last_state:=req_dma
//             }
//         }
//         is(rev_R_data_one_row)
//         {
//             // 该状态由dma每次写一个64b数据过来，都属于block的某一行
//             // 依次存入buf即可
//             val total_64b_per_row=((cur_block_col_num_ele*bits_per_ele)>>6)
//             // 每次写入一个64b，也就是8个buf单元，buf的col指针每次加8
//             when(io.write_buf_en)
//             {
//                 // 行
//                 for(buf_row<- 0 until 64)
//                 {
//                     when(buf_row.U===buf_row_ptr)
//                     {
//                         // 列
//                         for(buf_col<-0 until sysCfg.transposeBufCol by 8)
//                         {
//                             when(buf_col.U===cur_block_col_ptr)
//                             {
//                                 for(inner_ptr<-0 until 8){regArray(buf_row)(buf_col+inner_ptr):=dataIn_reformat(inner_ptr)}
//                             }
//                         }
//                     }
//                 }
//                 buf_64b_count_per_row:=buf_64b_count_per_row+1.U
//                 cur_block_col_ptr:=cur_block_col_ptr+8.U
//                 // 当前整行接收完毕
//                 when(buf_64b_count_per_row===(total_64b_per_row-1.U))
//                 {
//                     buf_row_ptr:=buf_row_ptr+1.U    // 指向下一行
//                     // 这时的buf_row_ptr还没有被加1，仍旧指向当前行
//                     p2s_state:=post_check
//                 }
//                 .otherwise  // 否则继续接受本行的下一个64b
//                 {p2s_state:=rev_R_data_one_row}
//             }
//         }
//         is(post_check)
//         {
//             // 该状态检查是buf一行读完了，还是整个buf填满了
//             // 读完一行就读下一行，整个填满了就进行write array，然后开始操作下一个block或者结束
//             // 若是整个block读完,buf_row_ptr应该归0了，因为操作完63行之后它被+1了（代码183行）
//             p2s_state:=Mux(buf_row_ptr===0.U,write_array_queue,pre_process_cur_block_row)
//         }
//         is(write_array_queue)
//         {
//             // 将buf送到queue,每次握手都完成一次传输并检查状态
//             // 并判断是整个R都完成了吗，还是还有没完成的，没完成就跳到pre_process_cur_block
//             split_data_queue.io.enq.valid:=true.B
//             when(split_data_queue.io.enq.fire)
//             {
//                 val cur_arrayID=MuxCase(0.U,(0 until 8).map { bit=>
//                     (bit.U===bit_ptr) -> (base_arrayID+arrayID_offset(bit))
//                 })
//                 val arrayAddr_enq=(cur_arrayID<<log2Ceil(core_configs.wordlineNums))+cur_block_col_elem_ptr_base+buf_elem_ptr
                
//                 val which_buf_row=MuxCase(buf_elem_ptr, Seq(
//                     (precision===0.U||precision===1.U)->(buf_elem_ptr>>2),
//                     (precision===2.U||precision===3.U)->(buf_elem_ptr>>1),
//                     (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->buf_elem_ptr)
//                 )
//                 val which_bit_in_uint8=MuxCase(bit_ptr, Seq(
//                     (precision===0.U||precision===1.U)->(((buf_elem_ptr&"b11".U)<<1)+bit_ptr),
//                     (precision===2.U||precision===3.U)->(((buf_elem_ptr&"b1".U)<<2)+bit_ptr),
//                     (precision===4.U||precision===5.U||precision===6.U||precision===7.U)->bit_ptr)
//                 )

//                 // for(row_ptr<-0 until dim)
//                 // {
//                 //     for(col_ptr <- 0 until 64)
//                 //     {
//                 //         when(row_ptr.U===which_buf_row)
//                 //         {res(col_ptr):=regArray(col_ptr)(row_ptr)(which_bit_in_uint8)}
//                 //         // {res(col_ptr):=regArrayT(row_ptr)(col_ptr)(which_bit_in_uint8)}
//                 //     }
//                 // }
//                 split_data_queue.io.enq.bits.split_val:=extractBits(regArrayT,which_buf_row,which_bit_in_uint8,dim)
//                 // split_data_queue.io.enq.bits.split_val:=split_val
//                 split_data_queue.io.enq.bits.arrayAddr:=arrayAddr_enq
//                 bit_ptr:=bit_ptr+1.U
                

//                 when(bit_ptr===precision)
//                 {
//                     bit_ptr:=0.U
//                     buf_elem_ptr:=buf_elem_ptr+1.U
//                     when(buf_elem_ptr===(cur_block_col_num_ele-1.U))
//                     {
//                         buf_elem_ptr:=0.U
//                         cur_block_col_elem_ptr_base:=cur_block_col_elem_ptr_base+cur_block_col_num_ele
//                         val has_written_cols=cur_block_col_elem_ptr_base+cur_block_col_num_ele
//                          // Triger trace rec !!!! End
//                         if(sysCfg.en_trace)
//                         {
//                             val trace_state = trace_state_some.get
//                             trace_state:=RecStateType.END_RUN
//                             p2s_state:=Mux(has_written_cols===num_column_of_R,send_trace,pre_process_cur_block)
//                         }
//                         else
//                         {
//                             p2s_state:=Mux(has_written_cols===num_column_of_R,p2s_idle,pre_process_cur_block)
//                         }
//                     }
//                 }
//             }
//         }
//     }


//     val is_queue_empty = !split_data_queue.io.deq.valid
//     val is_queue_has_val = split_data_queue.io.deq.valid
//     switch(deq_state)
//     {
//         is(queue_empty)
//         {
//             // queue has data
//             when(split_data_queue.io.deq.valid)
//             {
//                 deq_state:=write_array
//             }
//         }
//         is(write_array)
//         {
//             io.accessArray.valid:=is_queue_has_val
//             when(io.accessArray.fire)
//             {
//                 // deq
//                 split_data_queue.io.deq.ready:=true.B
//                 io.accessArray.bits.dataWrittenToBank:=split_data_queue.io.deq.bits.split_val
//                 io.accessArray.bits.optype:=AccessArrayType.WRITE
//                 io.accessArray.bits.addr:=split_data_queue.io.deq.bits.arrayAddr
//             }

//             when(is_queue_empty)
//             {
//                 deq_state:=queue_empty
//             }

//         }
//     }

//     def extractBits(arrayT: Seq[Seq[UInt]], row: UInt, bit: UInt, dim:Int): UInt = {
//         val res=WireInit(VecInit(Seq.fill(64)(0.U(8.W))))
//         for(row_ptr<-0 until dim)
//         {
//             when(row_ptr.U===row)
//             {
//                 for(col_ptr <- 0 until 64)
//                 {
//                     res(col_ptr):=arrayT(row_ptr)(col_ptr)
//                 }
//             }
            
//         }
//         Cat(res.map(_(bit)).reverse)
//     }

// }