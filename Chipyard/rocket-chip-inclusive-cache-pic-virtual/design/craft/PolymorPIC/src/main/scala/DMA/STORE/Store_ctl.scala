package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}


class Store_req(sysCfg:Sys_Config) extends Bundle {
    val byte_per_row= UInt(sysCfg._ISA_bytePerRow_sigLen.W)  // The unit is one byte, referring to the number of bytes per row.
    val row=UInt(sysCfg._ISA_nRow_sigLen.W) // #row
    val offset=UInt(sysCfg.offset_signLen.W)    // The offset that the DRAM must skip after accessing each row (measured in units of 1 byte).
    val baseAddr_DRAM= UInt(sysCfg.virtualAddrLen.W)
    val baseAddr_Array= UInt((sysCfg.accessCacheFullAddrLen).W)
}

// Specify the start address and length, both in bytes.
class Store_ctl(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val io = IO(new Bundle {
        // request from CMD
        val store_req = Flipped(Decoupled(new Store_req(sysCfg)))
        val busy = Output(Bool())

        // req to page split
        val dma_write_req = Decoupled(new DMA_write_req(sysCfg))
        val dma_busy=Input(Bool())

        // req bankFetch
        val bankFetchReq=Decoupled(new BankFetchReq(sysCfg))
        val fetch_busy=Input(Bool())
    })

    // init
    io.dma_write_req.valid:=false.B
    io.dma_write_req.bits:=DontCare

    // state
    val store_idle :: reqBankFetch :: req_dma :: wait_dma_finish :: Nil = Enum(4)
    val store_state=RegInit(store_idle)

    io.store_req.ready := (store_state===store_idle)
    val busy = (store_state=/=store_idle)
    io.busy := busy
    io.bankFetchReq.valid:=(store_state===reqBankFetch)

    // registers
    val offset_reg=RegInit(0.U(sysCfg.offset_signLen.W))
    val bytes_per_row_reg=RegInit(0.U(sysCfg.maxAccessBytesSigLen.W))   // The length is calculated based on the maximum total length, so the bit width is set large.
    val row_num_reg=RegInit(1.U(sysCfg._ISA_nRow_sigLen.W))
    val arrayAddr_ptr=RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val baseAddr_DRAM_reg= RegInit(0.U(sysCfg.virtualAddrLen.W))
    val baseAddr_Array_reg= RegInit(0.U(sysCfg.accessCacheFullAddrLen.W))
    val baseAddr_Array_offset_reg= RegInit(0.U(log2Ceil(sysCfg.core_config.bitlineNums/8).W))

    io.bankFetchReq.bits.nBytes:=bytes_per_row_reg
    io.bankFetchReq.bits.baseAddrDRAM:=baseAddr_DRAM_reg
    io.bankFetchReq.bits.baseAddrBankRow:=baseAddr_Array_reg
    io.bankFetchReq.bits.baseAddrBankRowOffset:=baseAddr_Array_offset_reg
    io.bankFetchReq.bits.globalLast:=true.B


    switch(store_state)
    {
        is(store_idle)
        {
            row_num_reg:=1.U
            when(io.store_req.fire)
            {
                val req_bits=io.store_req.bits
                val one_shot=(req_bits.byte_per_row===req_bits.offset)
                bytes_per_row_reg:=Mux(one_shot,req_bits.byte_per_row*(req_bits.row+1.U),req_bits.byte_per_row)
                offset_reg:=req_bits.offset
                row_num_reg:=Mux(one_shot,row_num_reg,row_num_reg+req_bits.row)
                baseAddr_DRAM_reg:=req_bits.baseAddr_DRAM
                baseAddr_Array_reg:=req_bits.baseAddr_Array
                baseAddr_Array_offset_reg:=0.U

                store_state:=reqBankFetch
            }
        }
        is(reqBankFetch)
        {
            when(io.bankFetchReq.fire)
            {
                store_state:=req_dma
            }
        }
        is(req_dma)
        {
            io.dma_write_req.valid:=true.B
            when(io.dma_write_req.fire)
            {
                // send req
                io.dma_write_req.bits.nBytes:=bytes_per_row_reg
                io.dma_write_req.bits.baseAddr_DRAM:=baseAddr_DRAM_reg

                // update state for next dma req
                baseAddr_DRAM_reg:=baseAddr_DRAM_reg+offset_reg
                val next_baseAddrBank=Cat(baseAddr_Array_reg,baseAddr_Array_offset_reg)+bytes_per_row_reg
                baseAddr_Array_reg:=next_baseAddrBank>>log2Ceil(sysCfg.core_config.bitlineNums/8)
                baseAddr_Array_offset_reg:=next_baseAddrBank(log2Ceil(sysCfg.core_config.bitlineNums/8)-1,0)
                row_num_reg:=row_num_reg-1.U

                store_state:=wait_dma_finish
            }
        }
        is(wait_dma_finish)
        {
            when(io.dma_busy===false.B&io.fetch_busy===false.B)
            {
                store_state:=Mux(row_num_reg===0.U,store_idle,reqBankFetch)
            }
        }
    }
}