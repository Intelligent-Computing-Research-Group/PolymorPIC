package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}


class Store_P_req(sysCfg:Sys_Config) extends Bundle {
    val len64_per_row=Input(UInt(log2Ceil(sysCfg._max_P_block_64b_per_row+1).W))  //单位为一个64b 是指每行几个64b
    val row=Input(UInt(log2Ceil(sysCfg.core_config.max_L_rows_per_block+1).W)) // 和L的行数是一样的
    val offset=Input(UInt(sysCfg._P_row_offset_sigLen.W))   // 每访问完一个64b，dram要跳过的offset(单位8个byte即64b)
    val baseAddr_DRAM= Input(UInt(32.W))
    val baseAddr_Array= Input(UInt((sysCfg.accessBankFullAddr_sigLen).W))
}

class Store_P_ctl(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{

    val io = IO(new Bundle {
        // request from CMD
        val store_req = Flipped(Decoupled(new Store_P_req(sysCfg)))
        val busy = Output(Bool())

        // request to DMA_read_ctl
        val dma_write_req = Decoupled(new DMA_write_req(sysCfg))
        val dma_busy=Input(Bool())

        // Trace
        val rec_req= if (sysCfg.en_trace) Some(Decoupled(new RecReq(sysCfg))) else None
    })

    // init
    io.dma_write_req.valid:=false.B
    io.dma_write_req.bits:=DontCare

    // state
    val store_idle :: req_dma :: wait_dma_finish :: send_trace:: Nil = Enum(4)
    val store_state=RegInit(store_idle)

    io.store_req.ready := (store_state===store_idle)
    val busy = (store_state=/=store_idle)
    io.busy := busy

    // registers
    val offset_reg=RegInit(0.U((sysCfg._P_row_offset_sigLen+3).W))
    val dram_ptr=RegInit(0.U(32.W))
    val len64b_per_row_reg=RegInit(0.U(log2Ceil(sysCfg._max_P_block_64b_per_row+1).W))
    val row_num_reg=RegInit(1.U(log2Ceil(sysCfg.core_config.max_L_rows_per_block+2).W))
    val arrayAddr_ptr=RegInit(0.U(sysCfg.accessBankFullAddr_sigLen.W))
    val baseAddr_DRAM_reg= RegInit(0.U((32.W)))
    val baseAddr_Array_reg= RegInit(0.U(sysCfg.accessBankFullAddr_sigLen.W))

    // Trace 
    val trace_state_some = if (sysCfg.en_trace) Some(RegInit(0.U(io.rec_req.get.bits.rec_state.getWidth.W))) else None
    if(sysCfg.en_trace)
    {
        val traceClient_store_P=new TraceClient(ClientName="STORE_P")
        val io_rec_req = io.rec_req.get
        val trace_state = trace_state_some.get
        io_rec_req.valid:= (store_state===send_trace)
        io_rec_req.bits.rec_state:=trace_state
        io_rec_req.bits.picAddr:=baseAddr_Array_reg

        // Command part is custom
        traceClient_store_P.addCustomField(field=row_num_reg,
                                     field_name="rowNum")
        traceClient_store_P.addCustomField(field=len64b_per_row_reg,
                                     field_name="len64_per_row")
        // 保持与上面一致
        io_rec_req.bits.command:=Cat(len64b_per_row_reg,
                                    row_num_reg)
        // 加到cfg
        sysCfg.traceCfg.helper.addTraceClient(traceClient_store_P)

        when(store_state===send_trace && io_rec_req.fire)
        {store_state:=Mux(trace_state===RecStateType.START_RUN,req_dma,store_idle)}
    }

    switch(store_state)
    {
        is(store_idle)
        {
            row_num_reg:=1.U
            when(io.store_req.fire)
            {
                val req_bits=io.store_req.bits
                len64b_per_row_reg:=req_bits.len64_per_row
                offset_reg:=req_bits.offset
                row_num_reg:=row_num_reg+req_bits.row
                baseAddr_DRAM_reg:=req_bits.baseAddr_DRAM
                baseAddr_Array_reg:=req_bits.baseAddr_Array

                // Triger trace rec !!!! Start
                if(sysCfg.en_trace)
                {   
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.START_RUN
                    store_state:=send_trace  
                }
                else
                {   store_state:=req_dma  }
            }
        }
        is(req_dma)
        {
            io.dma_write_req.valid:=true.B
            when(io.dma_write_req.fire)
            {
                // send req
                io.dma_write_req.bits.len:=len64b_per_row_reg
                io.dma_write_req.bits.baseAddr_DRAM:=baseAddr_DRAM_reg
                io.dma_write_req.bits.baseAddr_Array:= baseAddr_Array_reg

                // update state for next dma req
                baseAddr_DRAM_reg:=baseAddr_DRAM_reg+(offset_reg<<3)
                baseAddr_Array_reg:=baseAddr_Array_reg+len64b_per_row_reg
                row_num_reg:=row_num_reg-1.U

                store_state:=wait_dma_finish
            }
        }
        is(wait_dma_finish)
        {
            when(io.dma_busy===false.B)
            {
                // Triger trace rec !!!! End
                if(sysCfg.en_trace)
                {
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.END_RUN
                    store_state:=Mux(row_num_reg===0.U,send_trace,req_dma)
                }
                else
                {store_state:=Mux(row_num_reg===0.U,store_idle,req_dma)}
            }
        }
    } 
}