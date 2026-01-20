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

class P2S_R_T_ctl(sysCfg:Sys_Config,coreCfg:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
{
    val io = IO(new Bundle {
        // request from cmd
        val p2s_R_T_req = Flipped(Decoupled(new P2S_R_req(sysCfg)))
        val busy = Output(Bool())

        // request to dma
        val dmaReq = Decoupled(new Load_req(sysCfg))

        // dma write data to buffer here
        val dataInAvailable = Flipped(Decoupled(Bool()))
        val dataFromLoadCtl = Input(UInt(64.W))

        // write array
        val accessArray=Decoupled(new ReqPackage(sysCfg))
    })


    val p2sTReq_reg=Reg(new P2S_R_req(sysCfg))

    // Queue
    val dataInQueue=Module(new Queue(UInt(sysCfg.busWidth.W),4))
    dataInQueue.io.enq.bits:=io.dataFromLoadCtl
    dataInQueue.io.enq.valid:=false.B

    // Buffer Array
    val nBuf= (sysCfg.core_config.bitlineNums*8/sysCfg.busWidth)
    val bufArray=Reg(Vec(nBuf, UInt(sysCfg.busWidth.W)))
    val bytePerBuf = sysCfg.busWidth/8
    val bufArrayOutReFormat=Wire(Vec(sysCfg.core_config.bitlineNums, UInt(8.W))) // to byte
    for(i<- 0 until sysCfg.core_config.bitlineNums)
    {
        val idx=(i%bytePerBuf)*8
        bufArrayOutReFormat(i):=bufArray(i/bytePerBuf)(idx+7,idx)
    }

    val rowPtrLoad=RegInit(0.U(log2Ceil(sysCfg.core_config.wordlineNums+1).W))

    val rev_idle :: req_dma :: rev_data :: Nil = Enum(3)
    val REV_STATE=RegInit(rev_idle)


    io.p2s_R_T_req.ready:=(REV_STATE===rev_idle)
    io.dmaReq.valid:=(REV_STATE===rev_data)
    io.dataInAvailable.ready:=(REV_STATE===rev_data)&(dataInQueue.io.enq.ready)

    // ################# Array offset Part ######################
    val relative_offset_buf=Wire(Vec(7, UInt(4.W)))
    get_array_relatice_offset(relative_offset_buf,io.p2s_R_T_req.bits.bufNum)
    val arrayID_offset=Reg(Vec(8, UInt(5.W)))   // Offset relative to bit 0; assigned as tempResults upon receiving the request.
    val tempResults = Wire(Vec(8, UInt(5.W)))
    tempResults(0) := 0.U
    for(i <- 1 until 8) {tempResults(i):=relative_offset_buf((i-1))+tempResults(i-1)}

    // ################# DMA connection #################
    io.dmaReq.valid:=(REV_STATE===req_dma)
    io.dmaReq.bits.byte_per_row:=p2sTReq_reg.nCols
    io.dmaReq.bits.row:=1.U    // one row per req
    io.dmaReq.bits.offset:=DontCare
    io.dmaReq.bits.baseAddr_DRAM:=p2sTReq_reg.dramAddr
    io.dmaReq.bits.baseAddr_Array:=DontCare
    io.dmaReq.bits.padding:=false.B
    io.dmaReq.bits.padInfo:=DontCare
    io.dmaReq.bits.dataDir:=DataDir.p2sRTbuffer

    val bytePerAccess=sysCfg.busWidth/8
    val accessPerRow=(p2sTReq_reg.nCols>>log2Ceil(bytePerAccess))+
                        Mux((p2sTReq_reg.nCols&(bytePerAccess-1).U)===0.U,0.U,1.U)
    val revCnt=RegInit(0.U(log2Ceil(sysCfg.core_config.bitlineNums*8/sysCfg.busWidth+1).W))

    switch(REV_STATE)
    {
        is(rev_idle)
        {
            when(io.p2s_R_T_req.fire)
            {
                p2sTReq_reg:=io.p2s_R_T_req.bits
                rowPtrLoad:=0.U
                arrayID_offset:=tempResults
                REV_STATE:=req_dma
            }
        }
        is(req_dma)
        {
            when(io.dmaReq.fire)
            {
                p2sTReq_reg.dramAddr:=p2sTReq_reg.dramAddr+p2sTReq_reg.next_row_offset_bytes
                rowPtrLoad:=rowPtrLoad+1.U
                revCnt:=0.U
                REV_STATE:=rev_data
            }
        }
        is(rev_data)
        {
            when(io.dataInAvailable.fire)
            {
                dataInQueue.io.enq.valid:=true.B
                revCnt:=revCnt+1.U
                val lastAccessThisRow=(revCnt===(accessPerRow-1.U))
                val lastRow= (rowPtrLoad===p2sTReq_reg.nRows)
                REV_STATE:=Mux(lastRow&lastAccessThisRow,rev_idle,
                                Mux(lastAccessThisRow,req_dma,REV_STATE))
            }
        }
    }

    val split_idle :: deq_to_buf :: write_bank :: Nil = Enum(3)
    val SPLIT_STATE=RegInit(split_idle)

    // Pointer for dequeueing and writing to buffer
    val bufPtr=RegInit(0.U(nBuf.W))

    // Write array pointer
    val bitPtr=RegInit(0.U(3.W))
    val rowPtrStore=RegInit(0.U(log2Ceil(sysCfg.core_config.wordlineNums+1).W))

    for(bufIdx<-0 until nBuf)
    {
        bufArray(bufIdx):=Mux((SPLIT_STATE===deq_to_buf)&(dataInQueue.io.deq.fire)&(bufIdx.U===bufPtr),dataInQueue.io.deq.bits,bufArray(bufIdx))
    }

    dataInQueue.io.deq.ready:=(SPLIT_STATE===deq_to_buf)

    
    val curArrayID=MuxCase(0.U,(0 until 8).map { bit=>
                    (bit.U===bitPtr) -> (p2sTReq_reg.base_arrayID_to_store+arrayID_offset(bit))})

    io.accessArray.bits.dataWrittenToBank:=extractBits(bufArrayOutReFormat,bit=bitPtr)
    io.accessArray.bits.addr:=(curArrayID<<log2Ceil(coreCfg.wordlineNums))+rowPtrStore
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.valid:=(SPLIT_STATE===write_bank)

    io.busy:= (REV_STATE=/=rev_idle)|(SPLIT_STATE=/=split_idle)


    // logic：Each time, one row of the block is fetched from the front queue—with the dequeue count equal to the number of columns divided by 64—and then written into the split buffer.
    // Then, each time, a corresponding bit-slice is cut from the split buffer and written back to the array.
    switch(SPLIT_STATE)
    {
        is(split_idle)
        {
            when(io.p2s_R_T_req.fire)
            {
                p2sTReq_reg:=io.p2s_R_T_req.bits
                rowPtrStore:=0.U
                bufPtr:=0.U
                SPLIT_STATE:=req_dma
            }
        }
        is(deq_to_buf)
        {
            when(dataInQueue.io.deq.fire)
            {
                bufPtr:=bufPtr+1.U
                // Gethered, write back
                when(bufPtr===(accessPerRow-1.U))
                {
                    bufPtr:=0.U
                    SPLIT_STATE:=write_bank
                }
            }
        }
        is(write_bank)
        {
            when(io.accessArray.fire)
            {
                bitPtr:=bitPtr+1.U
                // Once all bits of the current row (all arrays for the current row) are written, increment the row pointer by one.
                // precision here is -1 itself.
                when(bitPtr===(p2sTReq_reg.precision))
                {
                    bitPtr:=0.U
                    rowPtrStore:=rowPtrStore+1.U
                    // All finish, idle
                    SPLIT_STATE:=Mux(rowPtrStore===(p2sTReq_reg.nRows-1.U),split_idle,deq_to_buf)
                }
            }
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
        loadReq.bits.dataDir:=DataDir.p2sRTbuffer
    }

    def extractBits(byteBufArray: Seq[UInt], bit: UInt): UInt = {
        Cat(byteBufArray.map(_(bit)).reverse)
    }

}
