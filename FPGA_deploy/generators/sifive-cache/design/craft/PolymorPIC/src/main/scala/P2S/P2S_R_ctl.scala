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
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}

class P2S_R_req(sysCfg:Sys_Config) extends Bundle {
    val nCols=UInt(log2Ceil(sysCfg.core_config.wordlineNums+1).W)    // Number of columns to read, max 1024
    val nRows=UInt(log2Ceil(sysCfg.core_config.wordlineNums+1).W)    // Read how many rows
    val precision=UInt(3.W)     
    val next_row_offset_bytes=UInt(sysCfg.offset_signLen.W)    // 1byte
    val base_arrayID_to_store=UInt(log2Ceil(sysCfg.numArraysTotal).W) // Which subarray to put the first selected bit map
    val bufNum=UInt(2.W)
    val dramAddr=UInt(sysCfg.virtualAddrLen.W)
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
        val dmaReq = Decoupled(new Load_req(sysCfg))

        // dma write data to buffer here
        val dataInAvailable = Flipped(Decoupled(Bool()))
        val dataFromLoadCtl = Input(UInt(64.W))

        // write array
        val accessArray=Decoupled(new ReqPackage(sysCfg))
    })

    val p2sReq_reg=Reg(new P2S_R_req(sysCfg))

    // buffer array
    val bufRow = sysCfg.core_config.bitlineNums      // 64
    val bufCol = sysCfg.busWidth/8                    // 8
    val regArray = Seq.fill(bufRow, bufCol)(Reg(UInt(8.W)))
    val regArrayT = regArray.transpose

    val rev_idle :: pre_process_cur_block :: req_dma :: write_mem_one_block_row :: update_cursor :: Nil = Enum(5)
    val REV_STATE=RegInit(rev_idle)

    // ##########################################################
    // ####################### Memory Part ######################
    // ##########################################################
    // 2 memory are used to store data from dma alternately
    // mem has state: EMPTY WRITING CAN_READ
    val mem0 =  Module(new P2S_SRAM(word_num=sysCfg.p2sR_mem_depth,io_width=64))
    val mem0_state = RegInit(0.U(2.W))
    val enWire_0 = WireInit(false.B)
    val writeEnWire_0=WireInit(false.B)
    val addrWire_0=WireInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))

    val mem1 =  Module(new P2S_SRAM(word_num=sysCfg.p2sR_mem_depth,io_width=64))
    val mem1_state = RegInit(0.U(2.W))
    val enWire_1=WireInit(false.B)
    val writeEnWire_1=WireInit(false.B)
    val addrWire_1=WireInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))
    
    // Choose which memory to write
    val dma_write_mem_chose = RegInit(0.U(1.W))
    val p2sReadMemChoice = RegInit(0.U(1.W))

    val writeMemAddr=RegInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))
    val readMemAddr=RegInit(0.U(log2Ceil(sysCfg.p2sR_mem_depth).W))

    mem0.io.en:=enWire_0
    mem0.io.writeEn:=writeEnWire_0
    mem0.io.addr:=Mux(mem0_state===MemState.WRITING,writeMemAddr,readMemAddr)
    mem0.io.dataIn:=io.dataFromLoadCtl

    mem1.io.en:=enWire_1
    mem1.io.writeEn:=writeEnWire_1
    mem1.io.addr:=Mux(mem1_state===MemState.WRITING,writeMemAddr,readMemAddr)
    mem1.io.dataIn:=io.dataFromLoadCtl
    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

    // ##########################################################
    // ################# Array offset Part ######################
    // ##########################################################
    val relative_offset_buf=Wire(Vec(7, UInt(4.W)))
    get_array_relatice_offset(relative_offset_buf,io.p2s_R_req.bits.bufNum)
    val arrayID_offset=Reg(Vec(8, UInt(5.W)))   // Offset relative to bit 0, assigned to tempResults when request is received
    val tempResults = Wire(Vec(8, UInt(5.W)))
    tempResults(0) := 0.U
    for(i <- 1 until 8) {tempResults(i):=relative_offset_buf((i-1))+tempResults(i-1)}
    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<


    // ##########################################################
    // ############## Some pointer registers ####################
    // ##########################################################
    val memMaxNbytes=sysCfg.p2sR_mem_depth*(sysCfg.busWidth/8)
    val memMaxNCols=(memMaxNbytes/sysCfg.core_config.bitlineNums).toInt      // For cases with less than 64 rows, still count as 64, as long as mem is greater than 64, it can hold
    val curBlockNCols=RegInit(0.U(log2Ceil(memMaxNbytes/sysCfg.core_config.bitlineNums+1).W)) // limitation=totalBytes/max R block row
    val curBlockNRows=p2sReq_reg.nRows
    val curBlockColPtr=RegInit(0.U(log2Ceil(sysCfg.core_config.wordlineNums+1).W))      // Current block column pointer (which column in the entire R block)
    val curBlockRowPtr=RegInit(0.U(log2Ceil(sysCfg.core_config.bitlineNums+1).W))      // Current block row pointer (which row in the entire R block)
    val curBlockDramBaseAddrPtr=RegInit(0.U(sysCfg.virtualAddrLen.W))    // Current block base address in memory
    val curRowDramAddrOffset=RegInit(0.U(sysCfg.virtualAddrLen.W))    // Current row offset in the current block based on curBlockDramAddrPtr
    
    val colReceivedCnt=RegInit(0.U(log2Ceil(memMaxNCols+1).W))
    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<


    // ##########################################################
    // ########################### DMA ##########################
    // ##########################################################
    io.dmaReq.valid:=(REV_STATE===req_dma)
    dmaReqSetup(io.dmaReq,
                curBlockDramBaseAddrPtr+curRowDramAddrOffset,
                curBlockNCols,
                p2sReq_reg.next_row_offset_bytes
                )
    // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

    // ##########################################################
    // ################# Buffer Operation State #################
    // ##########################################################
    val write_pic_idle :: check_readable_memory :: read_mem_and_write_buf :: split_data_enq :: update_write_pic_cursor :: Nil = Enum(5)
    val BUF_OP_STATE=RegInit(write_pic_idle)

    val blockNcolsInMem0=RegInit(0.U(log2Ceil(memMaxNCols+1).W))
    val blockNcolsInMem1=RegInit(0.U(log2Ceil(memMaxNCols+1).W))


    io.dataInAvailable.ready:=(REV_STATE===write_mem_one_block_row)
    io.p2s_R_req.ready:=(REV_STATE===rev_idle)
    io.busy := !((REV_STATE===rev_idle)&(BUF_OP_STATE===write_pic_idle))

    // Get data from DMA
    switch(REV_STATE)
    {
        is(rev_idle)
        {
            when(io.p2s_R_req.fire)
            {
                val req_bits=io.p2s_R_req.bits
                p2sReq_reg:=req_bits
                
                // Init pointers
                curBlockColPtr:=0.U
                writeMemAddr:=0.U

                // array offset
                arrayID_offset:=tempResults

                // write which memory. Inited from 0
                dma_write_mem_chose:=0.U
                mem0_state:= MemState.EMPTY
                mem1_state:= MemState.EMPTY
                // Init curBlockDramBaseAddrPtr to requested dram addr
                curBlockDramBaseAddrPtr:=req_bits.dramAddr

                REV_STATE:=pre_process_cur_block
            }
        }
        is(pre_process_cur_block)
        {
            // Idle memory situation, initially prefer 0
            val mem0_can_write= (mem0_state===MemState.EMPTY)
            val mem1_can_write= (mem1_state===MemState.EMPTY)
            val memAvailable=Mux(dma_write_mem_chose===0.U,mem0_can_write,mem1_can_write)
            when(memAvailable)
            {
                 // Calculate the number of elements per row for the current block.
                curBlockNCols:=Mux((curBlockColPtr+memMaxNCols.U)<=p2sReq_reg.nCols,
                                        memMaxNCols.U,
                                        p2sReq_reg.nCols-curBlockColPtr)
                curRowDramAddrOffset:=0.U
                curBlockRowPtr:=0.U

                mem0_state:=Mux(dma_write_mem_chose===0.U,MemState.WRITING,mem0_state)
                mem1_state:=Mux(dma_write_mem_chose===1.U,MemState.WRITING,mem1_state)      
                REV_STATE:=req_dma
            }
        }
        is(req_dma)     
        {
            when(io.dmaReq.fire)
            {
                colReceivedCnt:=0.U
                REV_STATE:=write_mem_one_block_row
            }
        }
        is(write_mem_one_block_row)
        {
            when(io.dataInAvailable.fire)
            {
                enWire_0:= (dma_write_mem_chose===0.U)
                writeEnWire_0:= (dma_write_mem_chose===0.U)
                enWire_1:= (dma_write_mem_chose===1.U)
                writeEnWire_1:= (dma_write_mem_chose===1.U)

                // Update cnt
                colReceivedCnt:=colReceivedCnt+(sysCfg.core_config.bitlineNums/8).U

                // check
                val this_row_end = (colReceivedCnt+(sysCfg.core_config.bitlineNums/8).U>=curBlockNCols)  // This row ends
                curBlockRowPtr:=Mux(this_row_end,curBlockRowPtr+1.U,curBlockRowPtr)  // This row ends, row ptr ++
                writeMemAddr:=writeMemAddr+1.U
                REV_STATE:=Mux(this_row_end,update_cursor,REV_STATE)

            }
        }
        is(update_cursor)
        {
            // If this row end
            val hasNextRow = !(curBlockRowPtr===p2sReq_reg.nRows)
            // If the whold R block end
            val allFinish = (curBlockColPtr+curBlockNCols===(p2sReq_reg.nCols))

            // Calculate the dram block offset, add the length of each row
            curRowDramAddrOffset:=curRowDramAddrOffset+p2sReq_reg.next_row_offset_bytes

            // If the current block ends
            // 1. Then update the base address of the block to the next block
            // 2. Update the memID to write to
            // 3. Update the current mem state to indicate it can be read
            // 4. Update the current curBlockColPtr
            curBlockDramBaseAddrPtr:=Mux(hasNextRow,curBlockDramBaseAddrPtr,curBlockDramBaseAddrPtr+curBlockNCols)   // No next row, current block ends
            dma_write_mem_chose:=Mux(hasNextRow,dma_write_mem_chose,dma_write_mem_chose+1.U)    // Current block ends
            mem0_state:=Mux(hasNextRow===false.B&&dma_write_mem_chose===0.U,MemState.CAN_READ,mem0_state)        // Notify buf can read
            mem1_state:=Mux(hasNextRow===false.B&&dma_write_mem_chose===1.U,MemState.CAN_READ,mem1_state)        

            blockNcolsInMem0:=Mux(hasNextRow===false.B&&dma_write_mem_chose===0.U,curBlockNCols,blockNcolsInMem0)
            blockNcolsInMem1:=Mux(hasNextRow===false.B&&dma_write_mem_chose===1.U,curBlockNCols,blockNcolsInMem1)

            curBlockColPtr:=Mux(hasNextRow===false.B,curBlockColPtr+curBlockNCols,curBlockColPtr)

            REV_STATE:=Mux(allFinish&(hasNextRow===false.B),rev_idle,
                            Mux(hasNextRow,req_dma,pre_process_cur_block))
        }
    }


    
    // ##########################################################
    // ############ Read Ram and Write Buf Logic ################
    // ##########################################################
    val writeBufRrowPtr=RegInit(0.U(log2Ceil(sysCfg.core_config.bitlineNums).W))  // Which row in the buf to write data to
    val curBlockColPtrGlobal=RegInit(0.U(log2Ceil(sysCfg.core_config.wordlineNums+1).W))   // The global column index of the 0th column of the current block in mem
    val curBufColPtrInBlock=RegInit(0.U(log2Ceil(memMaxNCols+1).W))   // The column index of the 0th column of the current data in the buf relative to the block
    val addrMem=curBufColPtrInBlock>>log2Ceil(sysCfg.busWidth/8)
    val curEnqBlockInBufColPtr=RegInit(0.U(log2Ceil(sysCfg.busWidth/8).W)) // The column index relative to this buf when writing buf data into the queue
    val bitPtr=RegInit(0.U(3.W))

    val blockNColInMem=Mux(p2sReadMemChoice===0.U,blockNcolsInMem0,blockNcolsInMem1)// Number of columns in the block stored in the current memory.
    val readMemInc=(blockNColInMem>>log2Ceil(sysCfg.busWidth/8))+Mux((blockNColInMem&((sysCfg.busWidth/8-1).U))===0.U,0.U,1.U)
    val maxColInBuf=(sysCfg.busWidth/8).U       // Buf can hold how many columns of block data
    val curBufNCols=Mux((curBufColPtrInBlock+maxColInBuf)<=blockNColInMem,
                                        maxColInBuf,
                                        blockNColInMem-curBufColPtrInBlock) // How many columns of the block are currently stored in the buf


    // Reformat to several bytes
    val mem0_out_reformat=WireInit(VecInit(Seq.fill(sysCfg.busWidth/8)(0.U(8.W))))
    val mem1_out_reformat=WireInit(VecInit(Seq.fill(sysCfg.busWidth/8)(0.U(8.W))))
    for(i<-0 until sysCfg.busWidth/8)
    {
        mem0_out_reformat(i):=mem0.io.dataOut(i*8+7,i*8)
        mem1_out_reformat(i):=mem1.io.dataOut(i*8+7,i*8)
    }

    val choosed_mem_dataOut=Mux(p2sReadMemChoice===0.U,mem0_out_reformat,
                                                            mem1_out_reformat)
    for(buf_row<- 0 until sysCfg.core_config.bitlineNums)
    {
        val choosed_mem_read_signal= Mux(p2sReadMemChoice===0.U, 
                                        enWire_0&(!writeEnWire_0),enWire_1&(!writeEnWire_1))
        val can_save = RegNext(choosed_mem_read_signal)&RegNext(writeBufRrowPtr===buf_row.U)
        for(buf_col<-0 until sysCfg.busWidth/8)
        {
            regArray(buf_row)(buf_col):=Mux(can_save,choosed_mem_dataOut(buf_col),regArray(buf_row)(buf_col))
        }
    }


    // Dataqueue
    val sliceDataQueue=Module(new Queue(new split_data(sysCfg.accessCacheFullAddrLen), 8))
    sliceDataQueue.io.enq.valid:=((BUF_OP_STATE===split_data_enq)&(RegNext(BUF_OP_STATE)=/=read_mem_and_write_buf))
    sliceDataQueue.io.enq.bits.split_val:=extractBits(
                                            regArrayT,curEnqBlockInBufColPtr,bitPtr,dim=8)

    val curArrayID=MuxCase(0.U,(0 until 8).map { bit=>
                    (bit.U===bitPtr) -> (p2sReq_reg.base_arrayID_to_store+arrayID_offset(bit))})
    val arrayAddrEnq=(curArrayID<<log2Ceil(coreCfg.wordlineNums))+curBlockColPtrGlobal+curBufColPtrInBlock+curEnqBlockInBufColPtr
    sliceDataQueue.io.enq.bits.arrayAddr:=arrayAddrEnq


    switch(BUF_OP_STATE)
    {
        is(write_pic_idle)
        {
            when(io.p2s_R_req.fire)
            {
                curBlockColPtrGlobal:=0.U
                p2sReadMemChoice:=0.U // From 0
                BUF_OP_STATE:=check_readable_memory
            }
        }
        is(check_readable_memory)
        {
            val mem0_can_read= (mem0_state===MemState.CAN_READ)
            val mem1_can_read= (mem1_state===MemState.CAN_READ)
            val mem_can_read_this_turn_to_check=Mux(p2sReadMemChoice===0.U,mem0_can_read,mem1_can_read)
            when(mem_can_read_this_turn_to_check)
            {
                readMemAddr:=0.U
                bitPtr:=0.U

                BUF_OP_STATE:=read_mem_and_write_buf
            }
        }
        is(read_mem_and_write_buf)
        {
            // which mem  !! Do NOT use a Mux here, otherwise it will cause an AND conflict when writing to memory above !!
            when(p2sReadMemChoice===0.U)
            {
                enWire_0:=true.B
                writeEnWire_0:=false.B
            }
            when(p2sReadMemChoice===1.U)
            {
                enWire_1:=true.B
                writeEnWire_1:=false.B
            }

            // Ptr update
            val writeBufFinish=(writeBufRrowPtr===(p2sReq_reg.nRows-1.U))
            writeBufRrowPtr:=Mux(writeBufFinish,0.U,writeBufRrowPtr+1.U)
            readMemAddr:=Mux(writeBufFinish,
                                addrMem+1.U,
                                readMemAddr+readMemInc)
            // Buffer write complete. Proceed with split and dequeue; otherwise, return to continue writing.
            BUF_OP_STATE:=Mux(writeBufFinish,split_data_enq,BUF_OP_STATE)
        }
        is(split_data_enq)
        {
            when(sliceDataQueue.io.enq.fire)
            {
                bitPtr:=bitPtr+1.U
                // All precision bits of the elements in the current buffer column have been written back.
                when(bitPtr===p2sReq_reg.precision)
                {
                    bitPtr:=0.U
                    curEnqBlockInBufColPtr:=curEnqBlockInBufColPtr+1.U
                    // All columns in the current bufT have been written back.
                    when(curEnqBlockInBufColPtr===(curBufNCols-1.U))
                    {
                        curEnqBlockInBufColPtr:=0.U
                        // Check if this is the final part of the block stored in memory.
                        val lastBlockColInMem=((curBufColPtrInBlock+curBufNCols)===blockNColInMem)
                        curBufColPtrInBlock:=Mux(lastBlockColInMem,0.U,curBufColPtrInBlock+curBufNCols)
                        BUF_OP_STATE:=Mux(lastBlockColInMem,update_write_pic_cursor,read_mem_and_write_buf)
                    }
                }
                    
            }
        }
        is(update_write_pic_cursor)
        {
            // Change memory state
            mem0_state:=Mux(p2sReadMemChoice===0.U,MemState.EMPTY,mem0_state)
            mem1_state:=Mux(p2sReadMemChoice===1.U,MemState.EMPTY,mem1_state)
            p2sReadMemChoice:=p2sReadMemChoice+1.U

            curBlockColPtrGlobal:=curBlockColPtrGlobal+blockNColInMem

            val no_data = (curBlockColPtrGlobal+blockNColInMem)===p2sReq_reg.nCols
            BUF_OP_STATE:=Mux(no_data,write_pic_idle,check_readable_memory)
        }
    }

    // ##########################################################
    // #################### Write Bank Part #####################
    // ##########################################################
    val queue_empty :: write_array :: Nil = Enum(2)
    val deq_state=RegInit(write_pic_idle)

    val is_queue_empty = !sliceDataQueue.io.deq.valid
    val is_queue_has_val = sliceDataQueue.io.deq.valid
    io.accessArray.valid := is_queue_has_val&(deq_state===write_array)
    io.accessArray.bits.dataWrittenToBank:=sliceDataQueue.io.deq.bits.split_val
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.bits.addr:=sliceDataQueue.io.deq.bits.arrayAddr

    sliceDataQueue.io.deq.ready:=false.B


    switch(deq_state)
    {
        is(queue_empty)
        {
            // queue has data
            when(sliceDataQueue.io.deq.valid)
            { deq_state:=write_array }
        }
        is(write_array)
        {
            when(io.accessArray.fire)
            { sliceDataQueue.io.deq.ready:=true.B}

            when(is_queue_empty)
            { deq_state:=queue_empty}
        }
    }

    def get_array_relatice_offset(offset: Vec[UInt],nBuf:UInt):Unit=
    {
        offset(0):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->1.U,(nBuf===1.U)->1.U))
        offset(1):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->3.U,(nBuf===1.U)->1.U))
        offset(2):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->1.U,(nBuf===1.U)->2.U))
        offset(3):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->3.U,(nBuf===1.U)->1.U))
        offset(4):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->1.U,(nBuf===1.U)->1.U))
        offset(5):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->3.U,(nBuf===1.U)->2.U))
        offset(6):=MuxCase(1.U, Seq((nBuf===3.U)->4.U,(nBuf===2.U)->1.U,(nBuf===1.U)->1.U))
    }

    def dmaReqSetup(loadReq: DecoupledIO[Load_req],dramAddr:UInt,nCol:UInt,offset:UInt):Unit=
    {
        loadReq.bits.baseAddr_Array:=0.U
        loadReq.bits.baseAddr_DRAM:=dramAddr
        loadReq.bits.byte_per_row:=nCol
        loadReq.bits.row:=1.U
        loadReq.bits.offset:=offset

        loadReq.bits.padding:=false.B
        loadReq.bits.padInfo:=DontCare
        loadReq.bits.dataDir:=DataDir.p2sRbuffer
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


class P2S_SRAM(word_num:Int,io_width:Int) extends Module 
{

    val io = IO(new Bundle {
        val addr = Input(UInt(log2Ceil(word_num).W))
        val writeEn = Input(Bool())
        val en = Input(Bool())
        val dataIn = Input(UInt(io_width.W))
        val dataOut = Output(UInt(io_width.W))
    })
    
    val mem = SyncReadMem(word_num,UInt(io_width.W))
    // single port 
    when(io.writeEn & io.en){
        mem.write(io.addr,io.dataIn)
    }
    
    io.dataOut := mem.read(io.addr, io.en&&(!io.writeEn))
}