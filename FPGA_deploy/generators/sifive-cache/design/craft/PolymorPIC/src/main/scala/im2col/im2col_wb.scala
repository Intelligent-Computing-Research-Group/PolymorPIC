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



class Im2col_wb(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val io = IO(new Bundle {
        // request from im2col top
        val im2col_req = Flipped(Decoupled(new IN2COL_req(sysCfg)))

        // request to bankfetch
        val bankFetchReq=Decoupled(new BankFetchReq(sysCfg))
        val bankFetch_busy=Input(Bool())

        // request to dma
        val dmaWB_req=Decoupled(new DMA_write_req(sysCfg))
        val dma_busy=Input(Bool())

        val busy = Output(Bool())
    })

    val im2colInfo_reg=Reg(new IN2COL_req(sysCfg))

    val sIdle :: send_dma_req :: wait_fetch_finish::Nil = Enum(3)
    val state=RegInit(sIdle)
    io.dmaWB_req.valid:= (state===send_dma_req)

    val fetchIdle :: send_fetch_req :: fetch_next_row :: Nil = Enum(3)
    val fetchMaskedDataState=RegInit(fetchIdle)


    io.bankFetchReq.valid:= (fetchMaskedDataState===send_fetch_req)

    // 计算flatten后的图总大小
    val fMapSize=im2colInfo_reg.featrueSize
    val fMapPaddedSize=im2colInfo_reg.featrueSize+(im2colInfo_reg.padSize<<1)
    val kernalSize=im2colInfo_reg.kernalSize
    val flatMapRow=kernalSize*kernalSize
    val maskSize=fMapPaddedSize-(kernalSize-1.U)                    // mask size
    val validMaskElemNumXorY=Mux(im2colInfo_reg.stride===1.U,maskSize,maskSize/im2colInfo_reg.stride+Mux(maskSize%im2colInfo_reg.stride===0.U,0.U,1.U))  // number of valid elements in each direction of the mask, corresponds to its size when stride=1
    val flatMapCol=validMaskElemNumXorY*validMaskElemNumXorY // number of valid elements in a mask, corresponds to the number of elements in a row after flattening
    val totalflatMapLen=(flatMapRow*flatMapCol)
    

    // Signals sent to DMA
    io.dmaWB_req.bits.nBytes:=totalflatMapLen
    io.dmaWB_req.bits.baseAddr_DRAM:=im2colInfo_reg.dst_baseAddr_DRAM

    // Signals sent to bankfetch
    io.bankFetchReq.bits:=DontCare

    // Parameters
    val maskNumSlide=kernalSize  // number of times the mask needs to slide horizontally or vertically, equal to kernal size
    val maskMapRowPtr=RegInit(0.U(log2Ceil(sysCfg.max_featrueSize+sysCfg.max_kernalSize).W))
    val maskSlideRowPtr=RegInit(0.U(sysCfg.max_kernalSize.W))
    val maskSlideColPtr=RegInit(0.U(sysCfg.max_kernalSize.W))

    val baseAddrBankWithOffsetMaskBase=RegInit(0.U((sysCfg.accessCacheFullAddrLen+sysCfg.accessCacheOffsetAddrLen).W))  // Moving within the top-left kernel
    val baseAddrBankWithOffsetMaskOffset=RegInit(0.U(
        log2Ceil(scala.math.pow(sysCfg.max_featrueSize+sysCfg.max_padSize,2).toInt).W
                                                    ))   
    val maskInRowPtr=RegInit(0.U(log2Ceil(sysCfg.max_featrueSize).W))    // Indicates the position in a row of the mask, only applicable when stride is not equal to 1
    val baseAddrBankWithOffsetPtr=baseAddrBankWithOffsetMaskBase+baseAddrBankWithOffsetMaskOffset+maskInRowPtr
    val bytePtr=RegInit(0.U(
        log2Ceil(scala.math.pow(sysCfg.max_featrueSize+sysCfg.max_padSize, 2).toInt).W
                        ))
    val dramPtr=bytePtr+im2colInfo_reg.dst_baseAddr_DRAM

    io.im2col_req.ready:=(state===sIdle)
    io.busy := !(state===sIdle)
    switch(state)
    {
        is(sIdle)
        {
            when(io.im2col_req.fire)
            {
                im2colInfo_reg:=io.im2col_req.bits
                state:=send_dma_req
            }
        }
        is(send_dma_req)        // Send req to dma
        {
            assert(totalflatMapLen<sysCfg.maxAccessBytes.U,"Can not support such big flatten map!")
            when(io.dmaWB_req.fire)
            {
                fetchMaskedDataState:=send_fetch_req
                // fetchStateStrideMoreThanOne:=Mux(im2colInfo_reg.stride>1.U,,fetch2Idle)
                // Send bank fetch req and initialize pointers
                // Set parameters for bank address
                baseAddrBankWithOffsetMaskBase:=(sysCfg.begin_im2colBuf_matID<<(sysCfg.core_config.core_addrLen+sysCfg.accessCacheOffsetAddrLen)).U
                maskMapRowPtr:=0.U
                maskSlideRowPtr:=0.U
                maskSlideColPtr:=0.U
                baseAddrBankWithOffsetMaskOffset:=0.U
                bytePtr:=0.U
                maskInRowPtr:=0.U
                state:=wait_fetch_finish
            }
        }
        is(wait_fetch_finish)  
        {
            state:=Mux(fetchMaskedDataState===fetchIdle,sIdle,state)
        }
    }


    switch(fetchMaskedDataState)
    {
        is(send_fetch_req)
        {
            // If it is the last group of data, add the last tag
            val isLast=(maskSlideRowPtr===(maskNumSlide-1.U))&(maskSlideColPtr===(maskNumSlide-1.U))&(maskMapRowPtr===(validMaskElemNumXorY-1.U))
            when(io.bankFetchReq.fire)
            {
                val oneStride=(im2colInfo_reg.stride===1.U)
                val thisIsInRowLast= (maskInRowPtr+im2colInfo_reg.stride)>=maskSize   // Only valid for !oneStride  
                io.bankFetchReq.bits.globalLast:=Mux(oneStride,isLast,isLast&(thisIsInRowLast))     // Last segment of many segments
                io.bankFetchReq.bits.nBytes:=Mux(oneStride,maskSize,1.U)
                io.bankFetchReq.bits.baseAddrDRAM:=dramPtr      // Used for automatic paging
                io.bankFetchReq.bits.baseAddrBankRow:=baseAddrBankWithOffsetPtr>>sysCfg.accessCacheOffsetAddrLen
                io.bankFetchReq.bits.baseAddrBankRowOffset:=baseAddrBankWithOffsetPtr(sysCfg.accessCacheOffsetAddrLen-1,0)

                // Reached the end of this row, need to jump to the next state to update pointers, only valid for !oneStride
                val maskRowEnd= (!oneStride)&thisIsInRowLast
                // !oneStride increments dramPtr by one each time
                bytePtr:=Mux(oneStride,bytePtr+maskSize,bytePtr+1.U)
                // !oneStride needs to increase by the length of a stride, reset to zero at the end
                maskInRowPtr:=Mux(oneStride,0.U,
                                Mux(maskRowEnd,0.U,maskInRowPtr+im2colInfo_reg.stride))
                
                // !oneStride needs to jump at the end of this row, for oneStride since the data in this row is continuous, it is sent all at once, so it jumps directly each time
                fetchMaskedDataState:=Mux(oneStride,fetch_next_row,
                                            Mux(maskRowEnd,fetch_next_row,fetchMaskedDataState))

                // If stride is not equal to 1, the operation is as follows
                // io.bankFetchReq.bits.nBytes only send one data
                // globalLast needs to satisfy the above isLast and also be the last element in this row
                // dramPtr+1 each time
                // fetchMaskedDataState state machine transitions to wait_fetch_finish when a full row is sent
                // baseAddrBankWithOffsetPtr increments by stride size each time, but if the row ends, it needs to reset to the starting position of the current row
            }
        }
        is(fetch_next_row)
        {
            when(!io.bankFetch_busy)
            {
                // 1.Single row of mask finished
                //      maskMapRowPtr++
                //      Update baseAddrBankWithOffsetPtr by adding the size of one mask
                val maskFinishOneRow=true.B

                // 2.Entire mask finished
                //      baseAddrBankWithOffsetMaskBase+1
                val maskFinish=(maskMapRowPtr===(validMaskElemNumXorY-1.U))&maskFinishOneRow

                // 2.1 Last mask sliding horizontally
                //      baseAddrBankWithOffsetMaskBase-(kernalsize-1)+fMapPaddedSize Switch to the next row baseAddrBankWithOffsetMaskBase needs to skip stride number of rows
                //      flatMapRow_Ptr++ (add the size of stride)
                //      maskSlideColPtr++
                val maskSlideFinish=(maskSlideColPtr===(maskNumSlide-1.U))&maskFinish

                // 2.2 All masks finished, all rows of the entire flatMap finished
                //      to idle
                val flatMapFinish= (maskSlideRowPtr===(maskNumSlide-1.U))&maskSlideFinish

                when(maskFinish) // 2.Entire mask finished
                {
                    baseAddrBankWithOffsetMaskOffset:=0.U
                    // The base shifts horizontally by one position.
                    // If it is the last mask of the current row, move to the starting mask position of the next row.
                    baseAddrBankWithOffsetMaskBase:=Mux(maskSlideFinish,baseAddrBankWithOffsetMaskBase-(kernalSize-1.U)+fMapPaddedSize,
                                                                        baseAddrBankWithOffsetMaskBase+1.U)
                    // maskMapRowPtr clear to 0
                    maskMapRowPtr:=0.U
                    // If it is the last position
                    maskSlideColPtr:=Mux(maskSlideFinish,0.U,maskSlideColPtr+1.U)
                    maskSlideRowPtr:=Mux(maskSlideFinish,maskSlideRowPtr+1.U,maskSlideRowPtr)
                }
                .otherwise // 1. Just the end of a row within the mask
                {
                    // bank offset added to the next row, the jump amount is fMapPaddedSize multiplied by the size of the stride!
                    baseAddrBankWithOffsetMaskOffset:=baseAddrBankWithOffsetMaskOffset+fMapPaddedSize*(im2colInfo_reg.stride)

                    // mask internal row pointer incremented by one
                    maskMapRowPtr:=maskMapRowPtr+1.U
                }

                fetchMaskedDataState:=Mux(flatMapFinish,fetchIdle,send_fetch_req)
            }
        }
    }


    def conn_im2col_req_load(dma: DecoupledIO[Load_req],im2col:IN2COL_req):Unit=
    {
        dma.bits.baseAddr_Array:=0.U
        dma.bits.baseAddr_DRAM:=im2col.src_baseAddr_DRAM
        dma.bits.byte_per_row:=im2col.featrueSize
        dma.bits.row:=im2col.featrueSize-1.U
        dma.bits.offset:=im2col.featrueSize

        dma.bits.padding:=true.B
        dma.bits.padInfo.padSize:=im2col.padSize
        dma.bits.padInfo.featrueBytesPerRow:=im2col.featrueSize
        dma.bits.padInfo.featrueRow:=im2col.featrueSize
    }

}