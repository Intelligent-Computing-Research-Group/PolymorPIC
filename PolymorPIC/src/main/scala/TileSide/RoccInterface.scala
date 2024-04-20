package PIC

import chisel3._
import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._  // for getting subsystembus parameter
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._

class RoccInterface(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config,opcode: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcode,nPTWPorts=1)
{
    // Send rocc command to L2
    val jmp_node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
                name = "rocc_jmp", sourceId = IdRange(0, 1))))))
    atlNode:=*jmp_node

    // receive TLB request from L2
    // val tlb_req_rev=LazyModule(new TLB_SlaveCtl(sysCfg))
    val tlb_req_rev=LazyModule(new PTW_query(sysCfg))
    tlb_req_rev.tlb_slave_node:=tlSlaveNode

    lazy val module = new RoccInterfaceImpl
    class RoccInterfaceImpl extends LazyRoCCModuleImp(this)
    {
        val (tl, edge) = jmp_node.out(0)
        val cmd_send_MMIO=(sysCfg.cmd_enq_MMIO)
        val tlb_ctl=tlb_req_rev.module

        val funct_reg=RegInit(0.U(7.W))
        val data_to_send=WireInit(0.U(64.W))

        val cmd_lsb=RegInit(0.U(64.W))
        val cmd_hsb=RegInit(0.U(64.W))
        val returned_state=RegInit(0.U(16.W))

        println("Rocc send address = ",cmd_send_MMIO)
        tl.a.bits := edge.Put(
            fromSource = 0.U,
            toAddress = cmd_send_MMIO,
            lgSize = 3.U,
            data = data_to_send
        )._2

        tl.a.valid:=false.B
        tl.d.ready:=false.B


        io.cmd.ready:=false.B
        io.resp.valid:=false.B
        io.resp.bits.data:=0.U
        val resp_rd=RegInit(0.U(5.W))
        io.resp.bits.rd:=resp_rd


        val ready :: send_cmd_lsb :: wait_resp_lsb::send_cmd_hsb :: wait_resp_hsb :: return_resp_rd ::Nil = Enum(6)
        val cmd_route_state = RegInit(ready)
        io.busy:= !(cmd_route_state===ready)

        // 无需入队的指令
        val immed_isa= (funct_reg===PolymorPIC_Configs._ISA_.QUERY)

        switch(cmd_route_state)
        {
            is(ready)
            {
                io.cmd.ready:=true.B
                when(io.cmd.fire)
                {
                    io.busy:=true.B
                    resp_rd:=io.cmd.bits.inst.rd
                    val cmd = io.cmd.bits
                    val funct = cmd.inst.funct
                    val rs1 = cmd.rs1
                    val rs2 = cmd.rs2

                    cmd_lsb:=Cat(0.U(25.W),funct,rs2(31,0))
                    cmd_hsb:=rs1
                    funct_reg:=funct

                    cmd_route_state:=send_cmd_lsb
                }
            }
            is(send_cmd_lsb)
            {
                // Send cmd to different queue and stall for resp or continue
                tl.a.valid:=true.B
                when(tl.a.fire)
                {
                    data_to_send:=cmd_lsb
                    cmd_route_state:=wait_resp_lsb
                }
            }
            is(wait_resp_lsb)
            {
                tl.d.ready:=true.B
                when (tl.d.fire)
                {
                    when(immed_isa)
                    {
                        returned_state:=tl.d.bits.data
                        cmd_route_state := return_resp_rd
                    }
                    .otherwise
                    {
                        cmd_route_state := send_cmd_hsb
                    }
                }
            }
            is(send_cmd_hsb)
            {
                tl.a.valid:=true.B
                when(tl.a.fire)
                {
                    data_to_send:=cmd_hsb
                    cmd_route_state:=wait_resp_hsb
                }
            }
            is(wait_resp_hsb)
            {
                tl.d.ready:=true.B
                when (tl.d.fire)
                {
                    when(tl.d.bits.data==="hFFFFFFFF".U)    // queue full
                    {cmd_route_state := send_cmd_hsb}       // resend
                    .otherwise{cmd_route_state := ready}
                }
            }
            is(return_resp_rd)
            {
                io.resp.valid:=true.B
                when(io.resp.fire)
                {
                    // 返回返回值
                    // 由于使用指令队列需要修改mat的state返回机制
                    io.resp.bits.data:=returned_state
                    cmd_route_state:=ready
                }
            }
        }

        // Old tlb ==
        // // receive request from l2's dma
        // val tlb_req_rev_module=Module(new FAKE_TLB)
        // // tlb core
        // // val tlb = tlb_ctl.module
        // val tlb = tlb_ctl

        // // Connection
        // tlb.io.trans_req <> tlb_req_rev_module.io.trans_req 
        // tlb.io.trans_resp <> tlb_req_rev_module.io.trans_resp
        // // io.ptw <> tlb.io.ptw
        // // io.interrupt := tlb.io.interrupt
        // Old tlb ==

        // PTW 
        tlb_req_rev.module.io.ptw <> io.ptw(0)

    }

}


// class WithRoccInterface extends Config((site, here, up) => {
//   case BuildRoCC => List(
//     (p: Parameters) => {
//          val rocc = LazyModule(new RoccInterface(Sys_Config(),PolymorPIC_Kernal_Config(),OpcodeSet.custom0)(p))
//             rocc
//     },
//     (p: Parameters) => {
//         val translator = LazyModule(new PTW_demo(OpcodeSet.custom1)(p))
//         translator
//     })
// })

class WithRoccInterface extends Config((site, here, up) => {
  case BuildRoCC => List(
    (p: Parameters) => {
         val rocc = LazyModule(new RoccInterface(Sys_Config(),PolymorPIC_Kernal_Config(),OpcodeSet.custom0)(p))
            rocc
    })
})
