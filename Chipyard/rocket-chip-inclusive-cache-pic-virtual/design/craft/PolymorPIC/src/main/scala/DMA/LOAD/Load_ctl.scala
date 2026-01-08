// Copyright (c) [Year] [name of copyright holder]
// [Software Name] is licensed under Mulan PSL v2.
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

class Load_req(sysCfg:Sys_Config) extends Bundle {
    val byte_per_row=UInt(sysCfg._ISA_bytePerRow_sigLen.W)  // The unit is one byte, means how many bytes to fetch per row
    val row=UInt(sysCfg._ISA_nRow_sigLen.W) // Number of rows to fetch
    val offset=UInt(sysCfg.offset_signLen.W)   // After accessing one row, the number of bytes to skip in DRAM, which is the size of a complete row of the entire matrix
    val baseAddr_DRAM= UInt(sysCfg.virtualAddrLen.W)
    val baseAddr_Array= UInt((sysCfg.accessCacheFullAddrLen).W)
    // Padding part
    val padding=Bool()
    val padInfo=new PadInfo(sysCfg)
    val dataDir=UInt(2.W)
}

class Load_ctl(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    // Only send and receive requests
    val offsetLenIn64B=log2Ceil(sysCfg.core_config.bitlineNums/8)
    val clients=LoadControllerClients.numClents
    val io = IO(new Bundle {
        // request from CMD normal load
        val load_req = Flipped(Decoupled(new Load_req(sysCfg)))
        // request from CMD im2col load
        val im2col_ld_req = Flipped(Decoupled(new Load_req(sysCfg)))
        // request form P2SL
        val p2sL_req = Flipped(Decoupled(new Load_req(sysCfg)))
        // request from P2SR
        val p2sR_req = Flipped(Decoupled(new Load_req(sysCfg)))
        // request from P2SR_T
        val p2sR_T_req = Flipped(Decoupled(new Load_req(sysCfg)))
        val busy = Output(Bool())

        // request to DMA_read_ctl  Fetch from mem
        val dma_read_req = Decoupled(new DMA_read_req(sysCfg))
        val dma_busy=Input(Bool())

        // request to Read Post Process  Write data to bank, including padding
        val post_process_req = Decoupled(new ReadPostProcess_req(sysCfg))
        val process_busy=Input(Bool())

        // last row
        val lastRow=Output(Bool())
    })

    // init
    io.dma_read_req.valid:=false.B
    io.dma_read_req.bits:=DontCare

    // state
    val load_idle :: req_dma ::set_post_process::  wait_dma_finish :: wait_post_process_finish ::Nil = Enum(5)
    val load_state=RegInit(load_idle)
    io.busy := (load_state=/=load_idle)

    // arbiter
    val arbiter = Module(new RRArbiter(new Load_req(sysCfg), clients))
    arbiter.io.in(LoadControllerClients.generalLoad)<>io.load_req
    arbiter.io.in(LoadControllerClients.im2colLoad)<>io.im2col_ld_req
    arbiter.io.in(LoadControllerClients.p2sL)<>io.p2sL_req
    arbiter.io.in(LoadControllerClients.p2sR)<>io.p2sR_req
    arbiter.io.in(LoadControllerClients.p2sRT)<>io.p2sR_T_req
    arbiter.io.out.ready:= (load_state===load_idle)

    // registers
    val offset_byte_reg=RegInit(0.U(sysCfg.offset_signLen.W))
    val bytes_per_row_reg=RegInit(0.U(sysCfg.maxAccessBytesSigLen.W))   // The length is calculated according to the maximum total length, considering continuous access case.
    val row_num_reg=RegInit(1.U(sysCfg._ISA_nRow_sigLen.W))
    val arrayAddr_ptr=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val baseAddr_DRAM_reg= RegInit(0.U((sysCfg.virtualAddrLen.W)))
    val baseAddr_Array_reg= RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val endAddr_Array_reg= RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val dataDir=RegInit(0.U(2.W))
    
    // Temporarily store pad information
    val ifPad=RegInit(false.B)
    val padInfo_reg=Reg(new PadInfo(sysCfg))

    io.post_process_req.valid:=(load_state===set_post_process)
    io.post_process_req.bits.bank_baseAddr:=baseAddr_Array_reg
    io.post_process_req.bits.bank_endAddr:=endAddr_Array_reg
    io.post_process_req.bits.padding:=ifPad
    io.post_process_req.bits.padInfo:=padInfo_reg
    io.post_process_req.bits.dataDir:=dataDir

    io.lastRow:=(row_num_reg===0.U)

    // Get data and 
    switch(load_state)
    {
        is(load_idle)
        {
            when(arbiter.io.out.fire)
            {
                val req_bits = arbiter.io.out.bits
                // Determine if memory access is contiguous; if so, treat all rows as a single row.
                val one_shot=(req_bits.byte_per_row===req_bits.offset)
                // If memory access is contiguous, unify the row count to 1 and set the row size to the total size.
                bytes_per_row_reg:=Mux(one_shot,req_bits.byte_per_row*(req_bits.row),req_bits.byte_per_row)
                offset_byte_reg:=req_bits.offset
                row_num_reg:=Mux(one_shot,1.U,req_bits.row)
                baseAddr_DRAM_reg:=req_bits.baseAddr_DRAM
                baseAddr_Array_reg:=req_bits.baseAddr_Array

                // Calculste the end address
                val padSize=Mux(req_bits.padding,req_bits.padInfo.padSize,0.U)
                val matRow=req_bits.row
                val matBytesBeforePad=(matRow)*req_bits.byte_per_row
                val matBytesAfterPad=(matRow+(padSize<<1))*(req_bits.byte_per_row+(padSize<<1))
                val endBytes=Cat(req_bits.baseAddr_Array,0.U(offsetLenIn64B.W))+matBytesAfterPad
                endAddr_Array_reg:= (endBytes>>log2Ceil(sysCfg.core_config.bitlineNums/8))-
                                                        Mux(endBytes(offsetLenIn64B-1,0)===0.U,1.U,0.U)

                ifPad:=req_bits.padding
                padInfo_reg:=req_bits.padInfo
                dataDir:=req_bits.dataDir

                load_state:=set_post_process
            }
        }
        is(set_post_process) // Need to set ReadPostProcess first
        {
            when(io.post_process_req.fire)
            {
                load_state:=req_dma
            }
        }
        is(req_dma)
        {
            io.dma_read_req.valid:=true.B
            when(io.dma_read_req.fire)
            {
                // send req
                io.dma_read_req.bits.nByte:=bytes_per_row_reg
                io.dma_read_req.bits.baseAddr_DRAM:=baseAddr_DRAM_reg

                // update state for next dma req
                baseAddr_DRAM_reg:=baseAddr_DRAM_reg+offset_byte_reg
                // baseAddr_Array_reg:=baseAddr_Array_reg+
                //                     (bytes_per_row_reg>>log2Ceil(sysCfg.core_config.bitlineNums))
                row_num_reg:=row_num_reg-1.U

                load_state:=wait_dma_finish
            }
        }
        is(wait_dma_finish)   
        {
            // Here, just wait for DMA to finish before requesting DMA again
            load_state:=Mux(io.dma_busy===false.B,
                            Mux(row_num_reg===0.U,wait_post_process_finish,req_dma),
                                load_state)
        }
        is(wait_post_process_finish)
        {
            // Need to initialize row_num_reg in advance to prevent a request from being accepted as soon as it returns to idle
            when(io.process_busy===false.B)
            {
            row_num_reg:=0.U
            load_state:=Mux(io.process_busy,load_state,load_idle)
        }
    }
}