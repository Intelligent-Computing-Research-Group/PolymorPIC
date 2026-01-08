package PIC

import chisel3._
import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._  // for getting subsystembus parameter
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket.{TLBConfig}


class RoccInterface(opcode: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcode,nPTWPorts=1)
{
    // Send rocc command to L2
    val jmp_node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
                name = "rocc_jmp", sourceId = IdRange(0, 1))))))
    atlNode:=*jmp_node

    // receive TLB request from L2
    // val tlb_req_rev=LazyModule(new TLB_SlaveCtl(sysCfg))
    val tlb_req_rev=LazyModule(new PTW_query)
    tlb_req_rev.tlb_slave_node:=tlSlaveNode

    lazy val module = new RoccInterfaceImpl
    class RoccInterfaceImpl extends LazyRoCCModuleImp(this)
    {
        val (tl, edge) = jmp_node.out(0)
        val cmd_send_MMIO=(MMIO.cmd_enq_MMIO)
        val tlb_ctl=tlb_req_rev.module

        val funct_reg=RegInit(0.U(12.W))

        val cmd_rs1=RegInit(0.U(64.W))
        val returned_date=RegInit(0.U(64.W))

        println("Rocc send address = ",cmd_send_MMIO)
        tl.a.bits := edge.Put(
            fromSource = 0.U,
            toAddress = cmd_send_MMIO+(funct_reg<<3),
            lgSize = 3.U,
            data = cmd_rs1
        )._2

        val ready :: send_cmd_rs1 :: wait_resp :: return_resp_rd ::delayState::Nil = Enum(5)
        val cmd_route_state = RegInit(ready)

        tl.d.ready:=(cmd_route_state===wait_resp)
        tl.a.valid:=(cmd_route_state===send_cmd_rs1)
        io.cmd.ready:=(cmd_route_state===ready)
        io.resp.valid:=(cmd_route_state===return_resp_rd)
        io.resp.bits.data:=0.U
        val resp_rd=RegInit(0.U(5.W))
        io.resp.bits.rd:=resp_rd
        io.resp.bits.data:=returned_date

        io.busy:= !(cmd_route_state===ready)

        // timmer for resend
        val resendTimer=RegInit(0.U(log2Ceil(50).W))
        resendTimer:=resendTimer+1.U

        // setParam need return value
        val hasReturn= (funct_reg===3.U)

        switch(cmd_route_state)
        {
            is(ready)
            {
                when(io.cmd.fire)
                {
                    io.busy:=true.B
                    resp_rd:=io.cmd.bits.inst.rd
                    val cmd = io.cmd.bits
                    val funct = cmd.inst.funct
                    val rs1 = cmd.rs1

                    cmd_rs1:=rs1
                    funct_reg:=funct

                    cmd_route_state:=send_cmd_rs1
                }
            }
            is(send_cmd_rs1)
            {
                // Send cmd to different queue and stall for resp or continue
                when(tl.a.fire)
                {   cmd_route_state:=wait_resp  }
            }
            is(wait_resp)
            {
                tl.d.ready:=true.B
                when (tl.d.fire)
                {
                    returned_date:=tl.d.bits.data
                    when(tl.d.bits.data==="hFFFFFFFFFFFFFFFF".U(64.W))    // setReg exe and rev
                    {
                        cmd_route_state := delayState
                    }
                    .otherwise
                    {
                        cmd_route_state := Mux(hasReturn,return_resp_rd,ready)
                    }
                }
            }
            is(return_resp_rd)
            {
                when(io.resp.fire)
                {   cmd_route_state:=ready  }
            }
            is(delayState)
            {
                cmd_route_state:=Mux(resendTimer===0.U,send_cmd_rs1,cmd_route_state)
            }
        }

        // PTW 
        tlb_req_rev.module.io.ptw <> io.ptw(0)
    }
}

class WithRoccInterface extends Config((site, here, up) => {
  case BuildRoCC => List(
    (p: Parameters) => {
         val rocc = LazyModule(new RoccInterface(OpcodeSet.custom0)(p))
            rocc
    })
})

case object RoccInterface extends Field[Option[TLBConfig]](None)

class WithRoccInterfaceBoom extends Config((site, here, up) => {
    case RoccInterface => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
         val rocc = LazyModule(new RoccInterface(OpcodeSet.custom0)(p))
            rocc
    })
})
