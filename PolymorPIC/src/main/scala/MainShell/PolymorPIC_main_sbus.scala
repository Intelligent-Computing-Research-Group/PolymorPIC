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

// Config file
case class PolymorPIC_Config()
case object PolymorPIC_cfg extends Field[Option[PolymorPIC_Config]](None)

// 这个模块模拟L2中的InclusiveCache那层, Bank部分在BankStore里面
class PolymorPIC_main_sbus(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule
{
    // DMA reader&writer
    val dma_reader=LazyModule(new DMA_reader_Ctl(sysCfg,core_configs))
    val dma_writer=LazyModule(new DMA_writer_Ctl(sysCfg,core_configs))
    val dmaReaderNode=dma_reader.dmaReader_node
    val dmaWriterNode=dma_writer.dmaWriter_node

    // P2S_L
    // val p2s_L=LazyModule(new P2S_R_ctl(sysCfg,core_configs)(p))
    
    // CMD_res
    val cmd_res=LazyModule(new CMD_resolver(sysCfg,core_configs)(p))
    val reveice_cmd_node=cmd_res.reveice_cmd_node
    
    // TLB_req_schedular 用于将tlb请求发送至tile并且接受结果
    val tlb_req_schedular=LazyModule(new TLB_req_schedular(sysCfg,core_configs)(p))
    val tlb_req_master_node=tlb_req_schedular.tl_master_tlb_req_node

    lazy val module = new Sys_main_impl
    class Sys_main_impl extends LazyModuleImp(this) 
    {
        // P2S_R
        val p2s_R_module=Module(new P2S_R_ctl(sysCfg,core_configs)(p))

        // P2S_L
        val p2s_L_module=Module(new P2S_L_ctl(sysCfg,core_configs)(p))

        // Load P
        val load_P_module=Module(new Load_P_ctl(sysCfg))
        
        // Store P
        val store_P_module=Module(new Store_P_ctl(sysCfg))

        // Accumulator
        val accumulator=Module(new Accumulator(sysCfg,core_configs)(p))

        // Access Arbiter
        val accessArb=Module(new AccessBank_Arb(sysCfg,core_configs))

        // Banks
        val banks=Module(new BanksShell(sysCfg,core_configs))

        // AutoLoadVec
        val autoLoadVec=Module(new AutoLoadL(sysCfg,core_configs))

        // switchCtl mainly for testing
        val switchCtl=Module(new SimSwitchCtl(sysCfg))

        // Trace
        val trace_module_some = if(sysCfg.en_trace) Some(Module(new TraceRec(sysCfg))) else None
        val matTrace_module_some = if(sysCfg.en_trace) Some(Module(new MatTracer(sysCfg))) else None
        
        val dmaReader_module=dma_reader.module
        val dmaWriter_module=dma_writer.module
        val cmd_res_module=cmd_res.module
        val tlb_req_schedular_module=tlb_req_schedular.module

        // DMA_reader & p2s_R
        p2s_R_module.io.dma_read_req <> dmaReader_module.io.req_p2s_R
        p2s_R_module.io.dataFromDMAreader:=dmaReader_module.io.dataTo_p2s
        p2s_R_module.io.write_buf_en:=dmaReader_module.io.write_p2s_R

        // DMA_reader & p2s_L
        p2s_L_module.io.dma_read_req<> dmaReader_module.io.req_p2s_L
        p2s_L_module.io.dataFromDMAreader:=dmaReader_module.io.dataTo_p2s
        p2s_L_module.io.write_buf_en:=dmaReader_module.io.write_p2s_L

        // DMA_reader & Load P
        load_P_module.io.dma_busy:=dmaReader_module.io.busy
        load_P_module.io.dma_read_req<>dmaReader_module.io.req_load_P

        // DMA_writer & Store P
        store_P_module.io.dma_busy:=dmaWriter_module.io.busy
        store_P_module.io.dma_write_req<>dmaWriter_module.io.req_write

        // TLB_req & DMA reader
        tlb_req_schedular_module.io.tlb_req(0) <> dmaReader_module.io.query_tlb_req
        dmaReader_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
        dmaReader_module.io.paddr:=tlb_req_schedular_module.io.paddr
        tlb_req_schedular_module.io.paddr_received(0):=dmaReader_module.io.paddr_received
        
        // TLB_req & DMA writer
        tlb_req_schedular_module.io.tlb_req(1) <> dmaWriter_module.io.query_tlb_req
        dmaWriter_module.io.paddr_valid:=tlb_req_schedular_module.io.paddr_valid
        dmaWriter_module.io.paddr:=tlb_req_schedular_module.io.paddr
        tlb_req_schedular_module.io.paddr_received(1):=dmaWriter_module.io.paddr_received

        // autoLoadL % Bank
        banks.io.load_vec_en:=autoLoadVec.io.writeVecEn
        banks.io.load_vec_addr:=autoLoadVec.io.load_vec_addr
        banks.io.vec_data:=autoLoadVec.io.vec_dataOut
        banks.io.request_vec <> autoLoadVec.io.request
        banks.io.response_vec <> autoLoadVec.io.response

        // >>>>>>>>> AccessArb Connection >>>>>>>>>>>>>>>>>>>>>>>

        // AccessArb <-> banks
        banks.io.cache_enable:=accessArb.io.cache_enable
        banks.io.cache_write:=accessArb.io.cache_write
        banks.io.cache_addr:=accessArb.io.cache_addr
        banks.io.cache_dataIn:=accessArb.io.cache_data_to_bank
        accessArb.io.cache_data_from_bank:=banks.io.cache_dataOut

        // AccessArb & DMA reader
        accessArb.io.request(sysCfg.dmaReader_accessArray_ID) <> dmaReader_module.io.accessArray
        
        // AccessArb & DMA writer
        accessArb.io.request(sysCfg.dmaWriter_accessArray_ID) <> dmaWriter_module.io.accessArray
        dmaWriter_module.io.dataReadFromArray := accessArb.io.dataReadFromBank

        // AccessArb & P2S_R
        accessArb.io.request(sysCfg.p2s_R_accessArray_ID) <> p2s_R_module.io.accessArray

        // AccessArb & P2S_L
        accessArb.io.request(sysCfg.p2s_L_accessArray_ID) <> p2s_L_module.io.accessArray

        // AccessArb & accumulatore
        accessArb.io.request(sysCfg.accumulator_accessArray_ID) <> accumulator.io.accessArrayReq
        accumulator.io.dataReadFromBank := accessArb.io.dataReadFromBank

        // AccessArb & autoLoadVec
        accessArb.io.request(sysCfg.autoLoadVec_accessArray_ID) <> autoLoadVec.io.accessArrayReq
        autoLoadVec.io.dataReadFromBank := accessArb.io.dataReadFromBank

        // <<<<<<<<<<<<<< AccessArb Connection <<<<<<<<<<<<<<<<<<<<<

        // >>>>>>>>> CmdRes Connection >>>>>>>>>>>>>>>>>>>>>>>

        // CmdRes & P2S_R
        cmd_res_module.io.p2s_R_req <> p2s_R_module.io.p2s_R_req
        cmd_res_module.io.busy_p2s_R := p2s_R_module.io.busy

        // CmdRes & P2S_L
        cmd_res_module.io.p2s_L_req <> p2s_L_module.io.p2s_L_req
        cmd_res_module.io.busy_p2s_L := p2s_L_module.io.busy

        // CmdRes & LOAD_P
        cmd_res_module.io.load_P_req <> load_P_module.io.load_req
        cmd_res_module.io.busy_load_P:=load_P_module.io.busy
        
        // CmdRes & STORE_P
        cmd_res_module.io.store_P_req <> store_P_module.io.store_req
        cmd_res_module.io.busy_store_P:=store_P_module.io.busy

        // CmdRes & Accumulator
        cmd_res_module.io.accumulate_req <> accumulator.io.accumulate_req
        cmd_res_module.io.accumulate_busy := accumulator.io.busy

        // CmdRes & setup
        banks.io.set_up_addr:=cmd_res_module.io.set_up_addr
        banks.io.set_up_io:=cmd_res_module.io.set_up_io

        // CmdRes & mat busy signal
        banks.io.query_mat_req(0)<>cmd_res_module.io.query_mat_req
        cmd_res_module.io.mat_busy:=banks.io.mat_busy

        // CmdRes & SwitchCtl
        switchCtl.io.switch_req<>cmd_res_module.io.switch_req
        switchCtl.io.switch_resp<>cmd_res_module.io.switch_resp
        
        // <<<<<<<<<<<<<< CmdRes Connection <<<<<<<<<<<<<<<<<<<<<


        // >>>>>>>>> Trace Connection >>>>>>>>>>>>>>>>>>>>>>>
        if(sysCfg.en_trace)
        {
          val trace_module = trace_module_some.get
          val matTrace_module=matTrace_module_some.get

          // p2sR
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("P2S_R")) <> p2s_R_module.io.rec_req.get
          
          // p2sL
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("P2S_L")) <> p2s_L_module.io.rec_req.get

          // LOAD_P
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("LOAD_P")) <> load_P_module.io.rec_req.get

          // STORE_P
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("STORE_P")) <> store_P_module.io.rec_req.get
          
          // ACC
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("ACC")) <> accumulator.io.rec_req.get

          // mat exe state
          trace_module.io.rec_request(sysCfg.traceCfg.ID_Map("EXE")) <> matTrace_module.io.rec_req
          matTrace_module.io.query_mat_req <> banks.io.query_mat_req(1)
          matTrace_module.io.mat_busy:=banks.io.mat_busy

          // save req
          trace_module.io.save_req <> cmd_res_module.io.save_trace_req.get
          cmd_res_module.io.save_busy.get:=trace_module.io.store_busy

          // access pic
          accessArb.io.request(sysCfg.trace_rec_accessArray_ID) <> trace_module.io.accessArray

          // gen yaml
          sysCfg.traceCfg.genConfigYAML()
        }
        // <<<<<<<<<<<<<< Trace Connection <<<<<<<<<<<<<<<<<<<<<
    }
}

// trait CanHaveExtraTileLinkConnection { this: BaseSubsystem =>
//   implicit val p: Parameters
//   p(PolymorPIC_cfg).map { k =>
//     val picDomain = sbus.generateSynchronousDomain
//     picDomain{
//       val polymorpic_sbus = LazyModule(new PolymorPIC_main_sbus(Sys_Config(),PolymorPIC_Kernal_Config())(p))
//       sbus.coupleFrom("stream-reader") { _ := polymorpic_sbus.dmaReaderNode }
//       sbus.coupleFrom("stream-writer") { _ := polymorpic_sbus.dmaWriterNode }
//       cbus.coupleFrom("tl_master_tlb_req") { _ := polymorpic_sbus.tlb_req_master_node }
//       polymorpic_sbus.reveice_cmd_node := cbus.coupleTo("rocc_ctrl") { TLBuffer(1) := TLFragmenter(cbus) := _}
//     }
//   }
// }


// Old version
// trait CanHaveExtraTileLinkConnection { this: BaseSubsystem =>
//   implicit val p: Parameters
//   p(PolymorPIC_cfg) .map { k =>
//     val polymor_pic_sys_side = LazyModule(new PolymorPIC_sys_main(Sys_Config(),PolymorPIC_Kernal_Config())(p))
//     sbus.coupleFrom("stream-reader") { _ := polymor_pic_sys_side.dmaReaderNode }
//     sbus.coupleFrom("stream-writer") { _ := polymor_pic_sys_side.dmaWriterNode }
//     cbus.coupleFrom("tl_master_tlb_req") { _ := polymor_pic_sys_side.tlb_req_master_node }
//     polymor_pic_sys_side.reveice_cmd_node := cbus.coupleTo("rocc_ctrl") { TLBuffer(1) := TLFragmenter(cbus) := _}
//   }
// }

trait CanHaveExtraTileLinkConnection { this: BaseSubsystem =>
  implicit val p: Parameters
  p(PolymorPIC_cfg).map { k =>
    val picDomain = sbus.generateSynchronousDomain
    picDomain{
      val polymorpic_sbus = LazyModule(new PolymorPIC_main_sbus(Sys_Config(),PolymorPIC_Kernal_Config())(p))
      mbus.coupleFrom("stream-reader") { _ := polymorpic_sbus.dmaReaderNode }
      sbus.coupleFrom("stream-writer") { _ := polymorpic_sbus.dmaWriterNode }
      cbus.coupleFrom("tl_master_tlb_req") { _ := polymorpic_sbus.tlb_req_master_node }
      polymorpic_sbus.reveice_cmd_node := cbus.coupleTo("rocc_ctrl") { TLBuffer(1) := TLFragmenter(cbus) := _}
    }
  }
}

class WithPolymorPIC_on_sbus extends Config((site, here, up) => {
    case PolymorPIC_cfg => Some(PolymorPIC_Config())
})