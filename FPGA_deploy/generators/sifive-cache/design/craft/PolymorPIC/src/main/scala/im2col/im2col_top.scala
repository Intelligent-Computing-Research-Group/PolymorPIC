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



class IN2COL_req(sysCfg:Sys_Config) extends Bundle {
    val src_baseAddr_DRAM=UInt(sysCfg.virtualAddrLen.W)
    val dst_baseAddr_DRAM=UInt(sysCfg.virtualAddrLen.W)
    val featrueSize=UInt(log2Ceil(sysCfg.max_featrueSize).W)
    val padSize=UInt(log2Ceil(sysCfg.max_padSize).W)
    val enWB=Bool()

    val kernalSize=UInt(log2Ceil(sysCfg.max_kernalSize).W)
    val stride=UInt(log2Ceil(sysCfg.max_stride).W)
}


class Im2col_top(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val io = IO(new Bundle {
        // request from CMD dispatch
        val im2col_req = Flipped(Decoupled(new IN2COL_req(sysCfg)))

        // Load req
        val dma_ld_req=Decoupled(new Load_req(sysCfg))
        val im2col_ld_busy = Input(Bool())


        // Store req 直接连到page split
        val dma_wb_req=Decoupled(new DMA_write_req(sysCfg))
        val dma_write_busy=Input(Bool())

        //  Store的时候也请求了bank fetch
        val bankFetchReq=Decoupled(new BankFetchReq(sysCfg))
        val bankFetch_busy=Input(Bool())

        val busy = Output(Bool())
    })


    val sIdle :: send_pad_req::wait_padFinish :: send_toCol_req::wait_to_col_finish::Nil = Enum(5)
    val state=RegInit(sIdle)

    io.im2col_req.ready:= (state===sIdle)
    io.busy:= !(state===sIdle)

    io.dma_ld_req.valid:= (state===send_pad_req)

    // regs
    val im2col_info_req=Reg(new IN2COL_req(sysCfg))

     // im2col wb module
    val im2col_wb=Module(new Im2col_wb(sysCfg))
    im2col_wb.io.im2col_req.valid:=(state===send_toCol_req)
    im2col_wb.io.dmaWB_req<>io.dma_wb_req
    im2col_wb.io.dma_busy:=io.dma_write_busy
    im2col_wb.io.bankFetch_busy:=io.bankFetch_busy
    im2col_wb.io.bankFetchReq<>io.bankFetchReq
    // bits sides connection
    im2col_wb.io.im2col_req.bits:=im2col_info_req

    // dma write
    conn_im2col_req_load(dma=io.dma_ld_req,im2col=im2col_info_req)

    switch(state)
    {
        is(sIdle)
        {
            when(io.im2col_req.fire)
            {
                im2col_info_req:=io.im2col_req.bits
                // Some checking
                assert(io.im2col_req.bits.stride<=4.U,"Current not support more than 4 strides!")
                assert(io.im2col_req.bits.kernalSize>=2.U,"Not support kernalSize=1 !")
                assert(io.im2col_req.bits.kernalSize<((im2col_info_req.padSize<<1)+im2col_info_req.featrueSize),
                                            "Not support kernalSize=1 !")
                assert(io.im2col_req.bits.kernalSize>=io.im2col_req.bits.stride,"????????!")
                state:=send_pad_req
            }
        }
        is(send_pad_req)
        {
            when(io.dma_ld_req.fire)
            {
                state:=wait_padFinish
            }
        }
        is(wait_padFinish)
        {
            // state:=Mux(enWB,sIdle,sIdle)
            when(!io.im2col_ld_busy)
            {state:=Mux(!im2col_info_req.enWB,sIdle,send_toCol_req)}
        }
        is(send_toCol_req)
        {
            when(im2col_wb.io.im2col_req.fire)
            {
                state:=wait_to_col_finish
            }
        }
        is(wait_to_col_finish)
        {
            state:=Mux(im2col_wb.io.busy,state,sIdle)
        }
    }


    def conn_im2col_req_load(dma: DecoupledIO[Load_req],im2col:IN2COL_req):Unit=
    {
        dma.bits.baseAddr_Array:=(sysCfg.begin_im2colBuf_matID<<sysCfg.core_config.core_addrLen).U
        dma.bits.baseAddr_DRAM:=im2col.src_baseAddr_DRAM
        dma.bits.byte_per_row:=im2col.featrueSize
        dma.bits.row:=im2col.featrueSize
        dma.bits.offset:=im2col.featrueSize

        dma.bits.padding:=true.B
        dma.bits.padInfo.padSize:=im2col.padSize
        dma.bits.padInfo.featrueBytesPerRow:=im2col.featrueSize
        dma.bits.padInfo.featrueRow:=im2col.featrueSize
        dma.bits.dataDir:=DataDir.bank
    }

}