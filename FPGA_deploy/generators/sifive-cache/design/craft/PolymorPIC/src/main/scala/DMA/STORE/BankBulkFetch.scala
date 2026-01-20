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

import form_icenet._

// About last
// Contineous accessing last: If it is the final segment during BankFetch pagination, attach a partialLast tag.
//                  In order to set the last data part when giving align:
// Global last：In scenarios with non-contiguous bank access, append the partialLast tag to the end of each continuous burst. Upon total completion, apply the globalLast indicator.

class BankFetchReq(sysCfg:Sys_Config) extends Bundle {
    val nBytes = UInt(sysCfg.maxAccessBytesSigLen.W)
    val baseAddrDRAM=UInt(sysCfg.virtualAddrLen.W)
    val baseAddrBankRow = UInt((sysCfg.accessCacheFullAddrLen).W)
    val baseAddrBankRowOffset = UInt(log2Ceil(sysCfg.core_config.bitlineNums/8).W)
    val globalLast = Bool()    // Used for the align last-field setting: indicates the final contiguous segment among a series of consecutive bursts.
}

// The module splits data based on whether it crosses page boundaries.
class BankFetch(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val dataBits = sysCfg.core_config.bitlineNums
    val bankAddrOffsetBits=log2Ceil(sysCfg.core_config.bitlineNums/8)
    val io = IO(new Bundle {
        //  from Store_ctl
        val req_mat_st = Flipped(Decoupled(new BankFetchReq(sysCfg)))
        // from im2col
        val req_im2col_st= Flipped(Decoupled(new BankFetchReq(sysCfg)))
        val busy=Output(Bool())

        // data out, to dma
        val aligned_out = Decoupled(new StreamChannel(dataBits))

        // req bank
        val accessBank=Decoupled(new ReqPackage(sysCfg))
        val dataFromBank=Input(UInt(dataBits.W))
        val dataReadValid=Input(Bool())
    })
    // deal with page split
    val atomicFetcher=Module(new AtomicBankFetch(sysCfg))
    io.accessBank<>atomicFetcher.io.accessBank
    atomicFetcher.io.dataFromBank:=io.dataFromBank
    atomicFetcher.io.dataReadValid:=io.dataReadValid
    val aligner = Module(new Aligner(dataBits))
    aligner.io.in <> atomicFetcher.io.out
    io.aligned_out <> aligner.io.out

    val reqReg=Reg(new BankFetchReq(sysCfg))

    val arbiter = Module(new RRArbiter(new BankFetchReq(sysCfg), 2))
    arbiter.io.in(0)<>io.req_mat_st
    arbiter.io.in(1)<>io.req_im2col_st
    val isReqFromIM2COL=RegInit(false.B)

    val sIdle :: sReqFetch :: wait_fetch_free::Nil = Enum(3)
    val state=RegInit(sIdle)
    arbiter.io.out.ready:=(state===sIdle)
    io.busy:= !(state===sIdle)
    atomicFetcher.io.req.valid:=(state===sReqFetch)
    atomicFetcher.io.req.bits:=DontCare


    val v_addr_ptr=RegInit(0.U(sysCfg.virtualAddrLen.W))
    val final_v_addr=reqReg.baseAddrDRAM+reqReg.nBytes
    val next_page_begin_addr = ((v_addr_ptr>>log2Ceil(sysCfg.pageSizeBytes))+1.U)<<log2Ceil(sysCfg.pageSizeBytes)
    val bankAddrWithOffset=Cat(reqReg.baseAddrBankRow,reqReg.baseAddrBankRowOffset)
    switch(state)
    {
        is(sIdle)
        {
            when(arbiter.io.out.fire)
            {
                assert(sysCfg.maxAccessBytes.U>=arbiter.io.out.bits.nBytes)
                assert(0.U<arbiter.io.out.bits.nBytes)
                reqReg:=arbiter.io.out.bits
                v_addr_ptr:=arbiter.io.out.bits.baseAddrDRAM
                isReqFromIM2COL:=(arbiter.io.chosen===1.U)
                state:=sReqFetch
            }
        }
        is(sReqFetch)
        {
            val validNBytes= Mux(final_v_addr>next_page_begin_addr,
                                            next_page_begin_addr-v_addr_ptr,
                                            final_v_addr-v_addr_ptr)
            val isLastPage= next_page_begin_addr>=final_v_addr

            when(atomicFetcher.io.req.fire)
            {
                atomicFetcher.io.req.bits.nBytes:=validNBytes
                atomicFetcher.io.req.bits.baseAddrBankRow:=reqReg.baseAddrBankRow
                atomicFetcher.io.req.bits.baseAddrBankRowOffset:=reqReg.baseAddrBankRowOffset
                // The function of the module it to split the data according to page boundaries.
                // globalLast is the last one segement of all the segments for a request
                // 2 cases to inform align of last:
                // 1. Is the global last segement and is the last after paging.
                // 2. Is not the global last segment but is the last segemnt of a page (it goes across pages) after paging, which is !isLastPage.
                atomicFetcher.io.req.bits.globalLast:=Mux(isReqFromIM2COL,
                        (reqReg.globalLast&isLastPage)||((!reqReg.globalLast)&(!isLastPage)),
                        true.B
                        )

                // update
                v_addr_ptr:=next_page_begin_addr
                val nextBankAddr=bankAddrWithOffset+validNBytes
                reqReg.baseAddrBankRow:=nextBankAddr>>bankAddrOffsetBits
                reqReg.baseAddrBankRowOffset:=nextBankAddr(bankAddrOffsetBits-1,0)

                state:=wait_fetch_free
            }
        }
        is(wait_fetch_free)
        {
            when(!atomicFetcher.io.busy_accessBank)
            {
                state:=Mux(v_addr_ptr<final_v_addr,sReqFetch,sIdle)
            }
        }
    }
}


// Used for a contiguous memory access segment in the Bank
class AtomicBankFetch(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val addrBits = sysCfg.accessCacheFullAddrLen+log2Ceil(sysCfg.core_config.bitlineNums/8)
    val dataBits = sysCfg.core_config.bitlineNums
    val beatBytes = dataBits / 8
    val byteAddrBits = log2Ceil(beatBytes)

    val io = IO(new Bundle {
        // request
        val req = Flipped(Decoupled(new BankFetchReq(sysCfg))) 
        val busy_accessBank=Output(Bool())

        // req bank
        val accessBank=Decoupled(new ReqPackage(sysCfg))
        val dataFromBank=Input(UInt(dataBits.W))
        val dataReadValid=Input(Bool())

        // Connected to align
        val out = Decoupled(new StreamChannel(dataBits))
    })

    // regs
    val nBytes=RegInit(0.U(sysCfg.maxAccessBytesSigLen.W))
    val ptrAddrBankRow_reg=RegInit(0.U((sysCfg.accessCacheFullAddrLen+1).W)) // ptrAddrBankRow_reg has rollback behavior, so leave one more bit
    val totalRowsNotEnq=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val endAddrBankRow_reg=RegInit(0.U((sysCfg.accessCacheFullAddrLen).W)) //end row will be accessed

    // Data read out is put into queue
    val data_queue=Module(new Queue(new StreamChannel(dataBits), 8))
    data_queue.io.enq.bits:=DontCare
    data_queue.io.deq.ready:=false.B
    data_queue.io.enq.valid:=false.B

    val is_queue_empty = !data_queue.io.deq.valid
    val is_queue_has_val = data_queue.io.deq.valid
    val is_queue_has_space= data_queue.io.enq.ready

    // for offset
    val fullKeep = ~0.U(beatBytes.W)  // 8个1
    val loffset = Reg(UInt(byteAddrBits.W))
    val roffset = Reg(UInt(byteAddrBits.W))
    val lkeep = fullKeep << loffset   // Representing which high bytes are needed for the first bus request of 8 bytes
    val rkeep = fullKeep >> roffset   // Representing which low bytes are needed for the last bus request of 8 bytes

    val partialLast=RegInit(false.B)  // Last of a contiguous segment, used for setting keep
    val globalLast=RegInit(false.B)  // The very last one, tagged with last
    val beginFetch=WireInit(false.B)

    val req_idle :: wait_deq_finish :: Nil = Enum(2)
    val req_state = RegInit(req_idle)
    io.req.ready:= (req_state===req_idle)

    val acsBank_idle :: acsBank_req :: Nil = Enum(2)
    val acsBank_state=RegInit(acsBank_idle)
    io.busy_accessBank:= !(acsBank_state===acsBank_idle)


    io.out.bits:=data_queue.io.deq.bits
    io.out.valid:=data_queue.io.deq.valid
    data_queue.io.deq.ready:=io.out.ready

    switch(req_state)
    {
        is(req_idle)
        {
            when(io.req.fire)
            {
                val req = io.req.bits
                val lastByteAddr = Cat(req.baseAddrBankRow,req.baseAddrBankRowOffset) + req.nBytes
                val startRow = req.baseAddrBankRow
                val endRow = Cat(0.U(1.W),lastByteAddr(addrBits-1, byteAddrBits)) -
                      Mux(lastByteAddr(byteAddrBits-1, 0) === 0.U, 1.U, 0.U) //end row will be accessed

                nBytes:=req.nBytes
                ptrAddrBankRow_reg:=req.baseAddrBankRow
                endAddrBankRow_reg:= endRow // end row will be accessed
                totalRowsNotEnq:= endRow-startRow+1.U
                globalLast:=req.globalLast

                // offset 
                loffset:=req.baseAddrBankRowOffset
                roffset:=Cat(endRow, 0.U(byteAddrBits.W)) - lastByteAddr

                beginFetch:=true.B
                req_state:=wait_deq_finish
            }
        }
        is(wait_deq_finish)
        {
            // When accessBank part has completed all requests, it can accept the next request
            // req_state:=Mux(is_queue_empty&(acsBank_state===acsBank_idle),req_idle,req_state)
            req_state:=Mux((acsBank_state===acsBank_idle)&(is_queue_empty),req_idle,req_state)
        }

    }



    io.accessBank.valid:=false.B
    io.accessBank.bits.optype:=AccessArrayType.READ
    io.accessBank.bits.addr:= ptrAddrBankRow_reg
    io.accessBank.bits.dataWrittenToBank:= 0.U

    // array_ptr_for_enq is used to indicate whether all data has been read
    // rowPtrBank is used to indicate the read address, which may roll back
    // rowPtrBank:=rowPtrBank+1.U and rollback cannot happen at the same time
    // At this time, it must be the next cycle after accessArray fire
    val isFirst=RegInit(false.B)

    when(io.dataReadValid)
    {
        // It may be that the previous delayed read had no space to write, so rowPtrBank needs to roll back
        when(!is_queue_has_space)
        {
            // roll back to re-read
            ptrAddrBankRow_reg:=ptrAddrBankRow_reg-1.U
        }
        .otherwise
        {
            data_queue.io.enq.valid:=true.B
            data_queue.io.enq.bits.data:=io.dataFromBank
            // The keep signal is only specifically set for the first and last segments; for all intermediate cases, it is set to all-ones.
            val seqLast=(totalRowsNotEnq===1.U)
            val isGlobalLast=globalLast&seqLast   // Obviously, if globalLast is asserted, partialLast must also be asserted, and the keep signal will be set accordingly.
            // Determined based on partialLast, it must be the last element within a specific contiguous segment.
            data_queue.io.enq.bits.keep:=Mux(isFirst&seqLast,lkeep&rkeep,       // It is head and tail
                                            Mux(isFirst,lkeep,
                                        Mux(seqLast,rkeep,fullKeep)))   // The last of current totalRows=1
            data_queue.io.enq.bits.last:= isGlobalLast

            totalRowsNotEnq:=totalRowsNotEnq-1.U
            isFirst:=Mux(isFirst,false.B,isFirst)
        }
    }

    switch(acsBank_state)
    {
        is(acsBank_idle)
        {
            isFirst:=true.B
            acsBank_state:=Mux(beginFetch,acsBank_req,acsBank_idle)
        }
        is(acsBank_req)
        {
            val can_access_bank= (ptrAddrBankRow_reg<=endAddrBankRow_reg)&&is_queue_has_space&&(totalRowsNotEnq>0.U) // 其实如果(ptrAddrBankRow_reg<=endAddrBankRow_reg)必有(totalRowsNotEnq>0.U)
            io.accessBank.valid:=can_access_bank

            when(io.accessBank.fire)
            {
                // io.accessBank.bits.addr:= ptrAddrBankRow_reg
                ptrAddrBankRow_reg:=ptrAddrBankRow_reg+1.U
            }

            when(totalRowsNotEnq===0.U)
            {
                acsBank_state:=acsBank_idle
            }

        }
    }


}