// package PIC

// import chisel3._
// import chisel3.util._
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.tile._

// import freechips.rocketchip.tilelink._
// import freechips.rocketchip.devices.tilelink._
// import org.chipsalliance.cde.config._
// import freechips.rocketchip.subsystem._
// import freechips.rocketchip.util._
// import freechips.rocketchip.regmapper._


// // 这个模块模拟L2中的InclusiveCache那层, Bank部分在BankStore里面
// class PolymorPIC_main_in_cache(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule
// {
//     // DMA reader&writer
//     val dma_reader=LazyModule(new DMA_reader_Ctl(sys_configs,core_configs))
//     val dma_writer=LazyModule(new DMA_writer_Ctl(sys_configs,core_configs))
//     val dmaReaderNode=dma_reader.dmaReader_node
//     val dmaWriterNode=dma_writer.dmaWriter_node

//     // P2S_L
//     // val p2s_L=LazyModule(new P2S_R_ctl(sys_configs,core_configs)(p))
    
//     // CMD_res
//     val cmd_res=LazyModule(new CMD_resolver(sys_configs,core_configs)(p))
//     val reveice_cmd_node=cmd_res.reveice_cmd_node
    
//     // TLB_req_schedular 用于将tlb请求发送至tile并且接受结果
//     val tlb_req_schedular=LazyModule(new TLB_req_schedular(sys_configs,core_configs)(p))
//     val tlb_req_master_node=tlb_req_schedular.tl_master_tlb_req_node

//     lazy val module = new Sys_main_impl
//     class Sys_main_impl extends LazyModuleImp(this) 
//     {
//       val io=IO(new Bundle {
//         // Send and receive switch req and resp
//         val switch_req=Decoupled(new SwitchInfo(sys_configs))
//         val switch_resp=Flipped(Decoupled(new SwitchResult(sys_configs)))

//         // ------------------for communicate with l2 bank pic part----------------
//          // Set up signals
//         val set_up_addr=Output(UInt(log2Ceil(sys_configs.total_valid_mats).W))
//         val set_up_io=Flipped(new SetUpIO(PolymorPIC_Configs.core_config))

//         // Load L
//         val request_vec=Vec(sys_configs.total_valid_mats, Flipped(Decoupled(new RequestAddress(core_configs))))
//         val response_vec=Vec(sys_configs.total_valid_mats, Decoupled(Bool()))
//         val load_vec_en =Output(Bool())
//         val load_vec_addr=Output(UInt(log2Ceil(sys_configs.total_valid_mats).W))
//         val vec_data=Output(UInt((core_configs.bitlineNums).W))

//         // block PIC mem access
//         val bank_busy=Vec(sys_configs.bank_num, Input(Bool()))

//         val pic_mem_enable = Output(Bool())
//         val pic_mem_write = Output(Bool())
//         val pic_mem_addr = Output(UInt(sys_configs.accessBankFullAddr_sigLen.W))
//         val pic_mem_dataIn = Output(UInt(core_configs.bitlineNums.W))
//         val pic_mem_dataOut = Input(UInt(core_configs.bitlineNums.W))

//         // query state
//         val query_matID=Output(UInt(log2Ceil(sys_configs.total_valid_mats).W))
//         val mat_busy=Input(Bool())
//       })

//         // P2S_R
//         val p2s_R_module=Module(new P2S_R_ctl(sys_configs,core_configs)(p))

//         // P2S_L
//         val p2s_L_module=Module(new P2S_L_ctl(sys_configs,core_configs)(p))

//          // Load P
//         val load_P_module=Module(new Load_P_ctl(sys_configs))
        
//         // Store P
//         val store_P_module=Module(new Store_P_ctl(sys_configs))

//         // Accumulator
//         val accumulator=Module(new Accumulator(sys_configs,core_configs)(p))

//         // Access Arbiter
//         val accessArb=Module(new AccessBank_Arb(sys_configs,core_configs))
//         accessArb.io.memBusy:=io.bank_busy.reduce(_||_)
//         // Banks
//         // val banks=Module(new BanksShell(sys_configs,core_configs))

//         // AutoLoadVec
//         val autoLoadVec=Module(new AutoLoadL(sys_configs,core_configs))
      
//         val dmaReader_module=dma_reader.module
//         val dmaWriter_module=dma_writer.module
//         val cmd_res_module=cmd_res.module
//         val tlb_req_schedular_module=tlb_req_schedular.module

//         // DMA_reader & p2s_R
//         p2s_R_module.io.dma_read_req <> dmaReader_module.io.req_p2s_R
//         p2s_R_module.io.dataFromDMAreader:=dmaReader_module.io.dataTo_p2s
//         p2s_R_module.io.write_buf_en:=dmaReader_module.io.write_p2s_R

//         // DMA_reader & p2s_L
//         p2s_L_module.io.dma_read_req<> dmaReader_module.io.req_p2s_L
//         p2s_L_module.io.dataFromDMAreader:=dmaReader_module.io.dataTo_p2s
//         p2s_L_module.io.write_buf_en:=dmaReader_module.io.write_p2s_L

//         // DMA_reader & Load P
//         load_P_module.io.dma_busy:=dmaReader_module.io.busy
//         load_P_module.io.dma_read_req<>dmaReader_module.io.req_load_P

//         // DMA_writer & Store P
//         store_P_module.io.dma_busy:=dmaWriter_module.io.busy
//         store_P_module.io.dma_write_req<>dmaWriter_module.io.req_write

//         // TLB_req & DMA reader
//         tlb_req_schedular_module.io.tlb_req(0) <> dmaReader_module.io.query_tlb_req
//         dmaReader_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
//         dmaReader_module.io.paddr:=tlb_req_schedular_module.io.paddr
//         tlb_req_schedular_module.io.paddr_received(0):=dmaReader_module.io.paddr_received
        
//         // TLB_req & DMA writer
//         tlb_req_schedular_module.io.tlb_req(1) <> dmaWriter_module.io.query_tlb_req
//         dmaWriter_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
//         dmaWriter_module.io.paddr:=tlb_req_schedular_module.io.paddr
//         tlb_req_schedular_module.io.paddr_received(1):=dmaWriter_module.io.paddr_received

//         // autoLoadL % Bank
//         io.load_vec_en:=autoLoadVec.io.writeVecEn
//         io.load_vec_addr:=autoLoadVec.io.load_vec_addr
//         io.vec_data:=autoLoadVec.io.vec_dataOut
//         io.request_vec <> autoLoadVec.io.request
//         io.response_vec <> autoLoadVec.io.response

//         // >>>>>>>>> AccessArb Connection >>>>>>>>>>>>>>>>>>>>>>>

//         // AccessArb <-> banks
//         io.pic_mem_enable:=accessArb.io.cache_enable
//         io.pic_mem_write:=accessArb.io.cache_write
//         io.pic_mem_addr:=accessArb.io.cache_addr
//         io.pic_mem_dataIn:=accessArb.io.cache_data_to_bank
//         accessArb.io.cache_data_from_bank:=io.pic_mem_dataOut

//         // AccessArb & DMA reader
//         accessArb.io.request(sys_configs.dmaReader_accessArray_ID) <> dmaReader_module.io.accessArray
        
//         // AccessArb & DMA writer
//         accessArb.io.request(sys_configs.dmaWriter_accessArray_ID) <> dmaWriter_module.io.accessArray
//         dmaWriter_module.io.dataReadFromArray := accessArb.io.dataReadFromBank

//         // AccessArb & P2S_R
//         accessArb.io.request(sys_configs.p2s_R_accessArray_ID) <> p2s_R_module.io.accessArray

//         // AccessArb & P2S_L
//         accessArb.io.request(sys_configs.p2s_L_accessArray_ID) <> p2s_L_module.io.accessArray

//         // AccessArb & accumulatore
//         accessArb.io.request(sys_configs.accumulator_accessArray_ID) <> accumulator.io.accessArrayReq
//         accumulator.io.dataReadFromBank := accessArb.io.dataReadFromBank

//         // AccessArb & autoLoadVec
//         accessArb.io.request(sys_configs.autoLoadVec_accessArray_ID) <> autoLoadVec.io.accessArrayReq
//         autoLoadVec.io.dataReadFromBank := accessArb.io.dataReadFromBank

//         // <<<<<<<<<<<<<< AccessArb Connection <<<<<<<<<<<<<<<<<<<<<

//         // >>>>>>>>> CmdRes Connection >>>>>>>>>>>>>>>>>>>>>>>

//         // CmdRes & P2S_R
//         cmd_res_module.io.p2s_R_req <> p2s_R_module.io.p2s_R_req
//         cmd_res_module.io.busy_p2s_R := p2s_R_module.io.busy

//         // CmdRes & P2S_L
//         cmd_res_module.io.p2s_L_req <> p2s_L_module.io.p2s_L_req
//         cmd_res_module.io.busy_p2s_L := p2s_L_module.io.busy

//         // CmdRes & LOAD_P
//         cmd_res_module.io.load_P_req <> load_P_module.io.load_req
//         cmd_res_module.io.busy_load_P:=load_P_module.io.busy
        
//         // CmdRes & STORE_P
//         cmd_res_module.io.store_P_req <> store_P_module.io.store_req
//         cmd_res_module.io.busy_store_P:=store_P_module.io.busy

//         // CmdRes & Accumulator
//         cmd_res_module.io.accumulate_req <> accumulator.io.accumulate_req
//         cmd_res_module.io.accumulate_busy := accumulator.io.busy

//         // CmdRes & setup
//         io.set_up_addr:=cmd_res_module.io.set_up_addr
//         io.set_up_io:=cmd_res_module.io.set_up_io

//         // CmdRes & mat busy signal
//         io.query_matID:=cmd_res_module.io.state_query_matID
//         cmd_res_module.io.mat_busy:=io.mat_busy

//         // CmdRes & SwitchCtl
//         io.switch_req<>cmd_res_module.io.switch_req
//         io.switch_resp<>cmd_res_module.io.switch_resp
        
//         // <<<<<<<<<<<<<< CmdRes Connection <<<<<<<<<<<<<<<<<<<<<
//     }
// }


