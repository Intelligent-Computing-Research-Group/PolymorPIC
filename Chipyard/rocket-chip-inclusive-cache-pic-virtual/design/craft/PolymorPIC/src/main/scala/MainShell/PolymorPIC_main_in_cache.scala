package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._

import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.regmapper._


class PolymorPIC_main_in_cache(sysCfg:Sys_Config)(implicit p: Parameters) extends LazyModule
{
    // DMA reader&writer
    val dma_reader=LazyModule(new DMA_read_page_split(sysCfg))
    val dma_writer=LazyModule(new DMA_write_page_split(sysCfg,sysCfg.core_config))
    val dmaReaderNode=dma_reader.dmaReader_node
    val dmaWriterNode=dma_writer.dmaWriter_node
    
    // CMD_res
    val cmd_res=LazyModule(new CMD_resolver(sysCfg)(p))
    val reveice_cmd_node=cmd_res.reveice_cmd_node
    
    // TLB_req_schedular. Used to dispatch TLB requests to the tile and receive the results.
    val tlb_req_schedular=LazyModule(new TLB_req_schedular(sysCfg)(p))
    val tlb_req_master_node=tlb_req_schedular.tl_master_tlb_req_node

    lazy val module = new Sys_main_impl
    class Sys_main_impl extends LazyModuleImp(this) 
    {

        // IO for SwitchCtl
        val io=IO(new Bundle {
            // SwitchIOs
            val switch_req=Decoupled(new SwitchInfo(sysCfg))
            val switch_resp=Flipped(Decoupled(new SwitchResult(sysCfg)))

            // Data
            val picMem_enable = Output(Bool()) 
            val picMem_write = Output(Bool())
            val picMem_addr = Output(UInt(sysCfg.accessCacheFullAddrLen.W))
            val picMem_dataOut = Output(UInt(sysCfg.core_config.bitlineNums.W))
            val picMem_dataIn = Input(UInt(sysCfg.core_config.bitlineNums.W))
            val mem_ready = Vec(sysCfg.numBanks,Input(Bool()))

            // Setup
            val set_up_addr=Output(UInt(log2Ceil(sysCfg.totalMatNum).W))
            val set_up_io=Output(new SetUpIO(sysCfg.core_config))

            // L
            val request_vec=Vec(sysCfg.totalMatNum, Flipped(Decoupled(new RequestAddress(sysCfg.core_config))))
            val response_vec=Vec(sysCfg.totalMatNum, Decoupled(Bool()))
            val load_vec_en =Output(Bool())
            val load_vec_addr=Output(UInt(log2Ceil(sysCfg.totalMatNum).W))
            val vec_data=Output(UInt((sysCfg.core_config.bitlineNums).W))

            // State
            val query_mat_req=Vec(sysCfg.query_clients,Decoupled(new QueryMatReq(sysCfg)))
            val mat_busy=Input(Bool())
        })

        // Load
        val load_ctl_module=Module(new Load_ctl(sysCfg))
        // read post process
        val load_post_process=Module(new ReadPostProcess(sysCfg))

        // Store
        val store_module=Module(new Store_ctl(sysCfg))
        val bankFetch_module=Module(new BankFetch(sysCfg))

        // im2col
        val im2col_ctl_module=Module(new Im2col_top(sysCfg))

        // P2S_R
        val p2s_R_module=Module(new P2S_R_ctl(sysCfg,sysCfg.core_config)(p))
        val p2s_R_T_module=Module(new P2S_R_T_ctl(sysCfg,sysCfg.core_config)(p))

        // P2S_L
        val p2s_L_module=Module(new P2S_L_ctl(sysCfg,sysCfg.core_config)(p))

        // Accumulator
        val accumulator=Module(new Accumulator(sysCfg)(p))

        // Access Arbiter
        val accessArb=Module(new AccessBank_Arb(sysCfg,sysCfg.core_config))

        // AutoLoadVec
        val autoLoadVec=Module(new AutoLoadL(sysCfg,sysCfg.core_config))
        
        val dmaReaderPageSplit_module=dma_reader.module
        val dmaWriterPageSplit_module=dma_writer.module
        val cmd_res_module=cmd_res.module
        val tlb_req_schedular_module=tlb_req_schedular.module

        // Load Controller & p2sR => for load
        p2s_R_module.io.dmaReq<>load_ctl_module.io.p2sR_req
        p2s_R_module.io.dataInAvailable<>load_post_process.io.dataToBufAvailableP2SR
        p2s_R_module.io.dataFromLoadCtl:=load_post_process.io.dataToBuf
        
        // Load Controller & p2sRT => for load
        p2s_R_T_module.io.dmaReq<>load_ctl_module.io.p2sR_T_req
        p2s_R_T_module.io.dataInAvailable<>load_post_process.io.dataToBufAvailableP2SRT
        p2s_R_T_module.io.dataFromLoadCtl:=load_post_process.io.dataToBuf

        // Load Controller & im2col_ctl_module  => for load
        im2col_ctl_module.io.dma_ld_req<>load_ctl_module.io.im2col_ld_req
        im2col_ctl_module.io.im2col_ld_busy:=load_ctl_module.io.busy

        // im2col_ctl_module & DMA_writer_page_split => for store
        im2col_ctl_module.io.dma_wb_req<>dmaWriterPageSplit_module.io.req_im2col_write
        im2col_ctl_module.io.dma_write_busy:=dmaWriterPageSplit_module.io.busy

        // im2col_ctl_module & BankFetch => for store
        im2col_ctl_module.io.bankFetchReq<>bankFetch_module.io.req_im2col_st
        im2col_ctl_module.io.bankFetch_busy:=bankFetch_module.io.busy

        // Load Controller & load_post_process => for padding & align
        load_ctl_module.io.post_process_req<>load_post_process.io.req
        load_ctl_module.io.process_busy:=load_post_process.io.busy

        // load_post_process & DMA_read_page_split
        load_post_process.io.unaligned_dataIn <> dmaReaderPageSplit_module.io.dataOut

        // Load Controller & DMA_read_page_split
        load_ctl_module.io.dma_busy:=dmaReaderPageSplit_module.io.busy
        load_ctl_module.io.dma_read_req<>dmaReaderPageSplit_module.io.req
        dmaReaderPageSplit_module.io.lastRow:=load_ctl_module.io.lastRow

        // Store Controller & DMA_writer_page_split
        store_module.io.dma_busy:=dmaWriterPageSplit_module.io.busy
        store_module.io.dma_write_req<>dmaWriterPageSplit_module.io.req_write

        // Store Controller & BankFetch
        store_module.io.bankFetchReq<>bankFetch_module.io.req_mat_st
        store_module.io.fetch_busy:=bankFetch_module.io.busy

        // DMA_writer_page_split & BulkBankFetch  dataIn and alignDataOut
        bankFetch_module.io.aligned_out <> dmaWriterPageSplit_module.io.dataIn

        // Read Controller & P2SL
        p2s_L_module.io.dma_read_req<> load_ctl_module.io.p2sL_req
        p2s_L_module.io.dataInAvailable<>load_post_process.io.dataToBufAvailableP2SL
        p2s_L_module.io.dataFromLoadCtl:=load_post_process.io.dataToBuf

        // TLB_req & dmaReaderPageSplit_module
        tlb_req_schedular_module.io.tlb_req(0) <> dmaReaderPageSplit_module.io.query_tlb_req
        dmaReaderPageSplit_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
        dmaReaderPageSplit_module.io.paddr:=tlb_req_schedular_module.io.paddr
        tlb_req_schedular_module.io.paddr_received(0):=dmaReaderPageSplit_module.io.paddr_received
        
        // TLB_req & DMA_writer_page_split
        tlb_req_schedular_module.io.tlb_req(1) <> dmaWriterPageSplit_module.io.query_tlb_req
        dmaWriterPageSplit_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
        dmaWriterPageSplit_module.io.paddr:=tlb_req_schedular_module.io.paddr
        tlb_req_schedular_module.io.paddr_received(1):=dmaWriterPageSplit_module.io.paddr_received

        // autoLoadL % Bank
        io.load_vec_en:=autoLoadVec.io.writeVecEn
        io.load_vec_addr:=autoLoadVec.io.load_vec_addr
        io.vec_data:=autoLoadVec.io.vec_dataOut
        io.request_vec <> autoLoadVec.io.request
        io.response_vec <> autoLoadVec.io.response

        // >>>>>>>>> AccessArb Connection >>>>>>>>>>>>>>>>>>>>>>>

        // AccessArb <-> banks
        io.picMem_enable:=accessArb.io.cache_enable
        io.picMem_write:=accessArb.io.cache_write
        io.picMem_addr:=accessArb.io.cache_addr
        io.picMem_dataOut:=accessArb.io.cache_data_to_bank
        accessArb.io.cache_data_from_bank:=io.picMem_dataIn
        accessArb.io.mem_ready:=io.mem_ready

        // AccessArb & load_post_process
        accessArb.io.request(sysCfg.load_post_process_ID) <> load_post_process.io.accessBank

        // AccessArb & bulkBankFetch_module
        accessArb.io.request(sysCfg.bankFetch_module_ID) <> bankFetch_module.io.accessBank
        bankFetch_module.io.dataFromBank := accessArb.io.dataReadFromBank
        bankFetch_module.io.dataReadValid := accessArb.io.dataReadValid(sysCfg.bankFetch_module_ID)

        // // AccessArb & P2SL
        accessArb.io.request(sysCfg.p2s_L_accessArray_ID) <> p2s_L_module.io.accessArray

        // // AccessArb & P2SR
        accessArb.io.request(sysCfg.p2s_R_accessArray_ID) <> p2s_R_module.io.accessArray

        // // AccessArb & P2SR_T
        accessArb.io.request(sysCfg.p2s_R_T_accessArray_ID) <> p2s_R_T_module.io.accessArray


        // AccessArb & accumulatore
        accessArb.io.request(sysCfg.accumulator_accessArray_ID) <> accumulator.io.accessArrayReq
        accumulator.io.dataReadFromBank := accessArb.io.dataReadFromBank
        accumulator.io.dataReadValid := accessArb.io.dataReadValid(sysCfg.accumulator_accessArray_ID)

        // AccessArb & autoLoadVec
        accessArb.io.request(sysCfg.autoLoadVec_accessArray_ID) <> autoLoadVec.io.accessArrayReq
        autoLoadVec.io.dataReadFromBank := accessArb.io.dataReadFromBank
        autoLoadVec.io.dataReadValid := accessArb.io.dataReadValid(sysCfg.autoLoadVec_accessArray_ID)

        // <<<<<<<<<<<<<< AccessArb Connection <<<<<<<<<<<<<<<<<<<<<

        // >>>>>>>>> CmdRes Connection >>>>>>>>>>>>>>>>>>>>>>>

        // CmdRes & P2S_R
        cmd_res_module.io.p2s_R_req <> p2s_R_module.io.p2s_R_req
        cmd_res_module.io.busy_p2s_R := p2s_R_module.io.busy

        // CmdRes & P2S_R_T
        cmd_res_module.io.p2s_R_T_req <> p2s_R_T_module.io.p2s_R_T_req
        cmd_res_module.io.busy_p2s_R_T := p2s_R_T_module.io.busy

        // CmdRes & P2S_L
        cmd_res_module.io.p2s_L_req <> p2s_L_module.io.p2s_L_req
        cmd_res_module.io.busy_p2s_L := p2s_L_module.io.busy

        // CmdRes & Load Controller => Load P
        cmd_res_module.io.load_P_req <> load_ctl_module.io.load_req
        cmd_res_module.io.busy_load:=load_ctl_module.io.busy
        
        // CmdRes & IM2COL
        cmd_res_module.io.im2col_req <> im2col_ctl_module.io.im2col_req
        cmd_res_module.io.im2col_busy:=im2col_ctl_module.io.busy
        
        // CmdRes & Store Controller => Store P
        cmd_res_module.io.store_P_req <> store_module.io.store_req
        cmd_res_module.io.busy_store:=store_module.io.busy

        // CmdRes & Accumulator
        cmd_res_module.io.accumulate_req <> accumulator.io.accumulate_req
        cmd_res_module.io.busy_acc := accumulator.io.busy

        // CmdRes & setup
        io.set_up_addr:=cmd_res_module.io.set_up_addr
        io.set_up_io:=cmd_res_module.io.set_up_io

        // CmdRes & mat busy signal
        io.query_mat_req(0)<>cmd_res_module.io.query_mat_req
        cmd_res_module.io.mat_busy:=io.mat_busy

        // CmdRes & SwitchCtl
        io.switch_req<>cmd_res_module.io.switch_req
        io.switch_resp<>cmd_res_module.io.switch_resp
        
        // <<<<<<<<<<<<<< CmdRes Connection <<<<<<<<<<<<<<<<<<<<<
    }
}


